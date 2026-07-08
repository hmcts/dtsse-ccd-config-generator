package uk.gov.hmcts.ccd.sdk.impl.cdam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "ccd.decentralised-runtime.cdam-attach", name = "enabled", havingValue = "true")
public class CdamAttachService {

  private final CaseDocumentHashScanner scanner;
  private final CaseDocumentAmClient caseDocumentAmClient;
  private final ObjectMapper objectMapper;

  public CdamAttachService(CaseDocumentHashScanner scanner,
                           CaseDocumentAmClient caseDocumentAmClient,
                           ObjectMapper objectMapper) {
    this.scanner = scanner;
    this.caseDocumentAmClient = caseDocumentAmClient;
    this.objectMapper = objectMapper;
  }

  public JsonNode attachNewDocumentsAndStripHashes(DecentralisedCaseEvent event, String authorisation,
                                                   JsonNode submittedData) {
    JsonNode existingData = event.getCaseDetailsBefore() == null
        ? null
        : objectMapper.valueToTree(event.getCaseDetailsBefore().getData());
    var tokens = scanner.findNewDocumentHashTokens(existingData, submittedData);
    JsonNode strippedData = scanner.stripDocumentHashes(submittedData);

    if (tokens.isEmpty()) {
      return strippedData;
    }

    var metadata = new CaseDocumentsMetadata(
        String.valueOf(event.getCaseDetails().getReference()),
        event.getEventDetails().getCaseType(),
        event.getCaseDetails().getJurisdiction(),
        tokens
    );

    try {
      caseDocumentAmClient.attach(authorisation, metadata);
      log.info("Attached {} CDAM document(s) to case {}", tokens.size(), metadata.caseId());
      return strippedData;
    } catch (RuntimeException ex) {
      throw new CdamAttachException("Unable to attach CDAM documents to case " + metadata.caseId(), ex);
    }
  }

  public static Optional<CdamAttachService> optional(CdamAttachService service) {
    return Optional.ofNullable(service);
  }
}
