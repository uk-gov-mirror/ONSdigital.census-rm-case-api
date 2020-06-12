package uk.gov.ons.census.caseapisvc.service;

import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gov.ons.census.caseapisvc.exception.CaseIdInvalidException;
import uk.gov.ons.census.caseapisvc.exception.CaseIdNotFoundException;
import uk.gov.ons.census.caseapisvc.exception.CaseReferenceNotFoundException;
import uk.gov.ons.census.caseapisvc.exception.QidNotFoundException;
import uk.gov.ons.census.caseapisvc.exception.UPRNNotFoundException;
import uk.gov.ons.census.caseapisvc.exception.UacQidLinkWithNoCaseException;
import uk.gov.ons.census.caseapisvc.model.dto.EventDTO;
import uk.gov.ons.census.caseapisvc.model.dto.FulfilmentRequestDTO;
import uk.gov.ons.census.caseapisvc.model.dto.PayloadDTO;
import uk.gov.ons.census.caseapisvc.model.dto.ResponseManagementEvent;
import uk.gov.ons.census.caseapisvc.model.dto.UacQidCreatedPayloadDTO;
import uk.gov.ons.census.caseapisvc.model.entity.Case;
import uk.gov.ons.census.caseapisvc.model.entity.UacQidLink;
import uk.gov.ons.census.caseapisvc.model.repository.CaseRepository;
import uk.gov.ons.census.caseapisvc.model.repository.UacQidLinkRepository;

@Service
public class CaseService {
  private static final Logger log = LoggerFactory.getLogger(CaseService.class);
  private static final String FULFILMENT_REQUEST_EVENT_TYPE = "FULFILMENT_REQUESTED";

  private final CaseRepository caseRepo;
  private final UacQidLinkRepository uacQidLinkRepository;
  private RabbitTemplate rabbitTemplate;

  @Value("${queueconfig.events-exchange}")
  private String eventsExchange;

  @Value("${queueconfig.fulfilment-event-routing-key}")
  private String fulfilmentEventRoutingKey;

  @Autowired
  public CaseService(
      CaseRepository caseRepo,
      UacQidLinkRepository uacQidLinkRepository,
      RabbitTemplate rabbitTemplate) {
    this.caseRepo = caseRepo;
    this.uacQidLinkRepository = uacQidLinkRepository;
    this.rabbitTemplate = rabbitTemplate;
  }

  public List<Case> findByUPRN(String uprn, boolean validAddressOnly) {
    log.debug("Entering findByUPRN");

    if (validAddressOnly) {
      return caseRepo
          .findByUprnAndAddressInvalidFalse(uprn)
          .orElseThrow(() -> new UPRNNotFoundException(uprn));
    } else {
      return caseRepo.findByUprn(uprn).orElseThrow(() -> new UPRNNotFoundException(uprn));
    }
  }

  public Case findByCaseId(String caseId) {
    log.debug("Entering findByCaseId");

    UUID caseIdUUID = validateAndConvertCaseIdToUUID(caseId);

    return caseRepo
        .findByCaseId(caseIdUUID)
        .orElseThrow(() -> new CaseIdNotFoundException(caseIdUUID.toString()));
  }

  public Case findByReference(long reference) {
    log.debug("Entering findByReference");

    return caseRepo
        .findByCaseRef(reference)
        .orElseThrow(() -> new CaseReferenceNotFoundException(reference));
  }

  public Case findCaseByQid(String qid) {
    UacQidLink uacQidLink =
        uacQidLinkRepository.findByQid(qid).orElseThrow(() -> new QidNotFoundException(qid));

    if (uacQidLink.getCaze() == null) {
      throw new UacQidLinkWithNoCaseException(qid);
    }

    return uacQidLink.getCaze();
  }

  public List<Case> findCCSCasesByPostcode(String postcode) {
    return caseRepo.findCCSCasesByPostcodeIgnoringCaseAndSpaces(postcode);
  }

  public boolean caseExistsByCaseId(String caseId) {
    UUID caseIdUUID = validateAndConvertCaseIdToUUID(caseId);
    return caseRepo.existsCaseByCaseId(caseIdUUID);
  }

  public UacQidLink findCCSUacQidLinkByCaseId(String caseId) {
    UUID caseIdUUID = validateAndConvertCaseIdToUUID(caseId);
    return uacQidLinkRepository
        .findOneByCcsCaseIsTrueAndCazeCaseIdAndCazeSurvey(caseIdUUID, "CCS")
        .orElseThrow(() -> new QidNotFoundException(caseIdUUID));
  }

  private UUID validateAndConvertCaseIdToUUID(String caseId) {
    UUID caseIdUUID;

    try {
      caseIdUUID = UUID.fromString(caseId);
    } catch (IllegalArgumentException iae) {
      throw new CaseIdInvalidException(caseId);
    }

    return caseIdUUID;
  }

  public void buildAndSendTelephoneCaptureFulfilmentRequest(
      String caseId,
      String fulfilmentCode,
      String individualCaseId,
      UacQidCreatedPayloadDTO uacQidCreated) {
    FulfilmentRequestDTO fulfilmentRequestDTO = new FulfilmentRequestDTO();
    fulfilmentRequestDTO.setCaseId(caseId);
    fulfilmentRequestDTO.setFulfilmentCode(fulfilmentCode);
    fulfilmentRequestDTO.setUacQidCreated(uacQidCreated);

    EventDTO eventDTO = new EventDTO();
    eventDTO.setType(FULFILMENT_REQUEST_EVENT_TYPE);
    eventDTO.setDateTime(OffsetDateTime.now());
    eventDTO.setTransactionId(UUID.randomUUID().toString());

    if (!StringUtils.isEmpty(individualCaseId)) {
      fulfilmentRequestDTO.setIndividualCaseId(individualCaseId);
      log.with("caseId", caseId)
          .with("individualCaseId", individualCaseId)
          .with("transactionId", eventDTO.getTransactionId())
          .debug("Sending UAC QID created event");
    } else {
      log.with("caseId", caseId)
          .with("transactionId", eventDTO.getTransactionId())
          .debug("Sending UAC QID created event");
    }

    PayloadDTO payloadDTO = new PayloadDTO();
    payloadDTO.setFulfilmentRequest(fulfilmentRequestDTO);

    ResponseManagementEvent responseManagementEvent =
        new ResponseManagementEvent(eventDTO, payloadDTO);

    rabbitTemplate.convertAndSend(
        eventsExchange, fulfilmentEventRoutingKey, responseManagementEvent);
  }
}
