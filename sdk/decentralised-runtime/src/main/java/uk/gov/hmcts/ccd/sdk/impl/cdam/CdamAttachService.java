package uk.gov.hmcts.ccd.sdk.impl.cdam;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClient;
import uk.gov.hmcts.reform.ccd.document.am.model.CaseDocumentsMetadata;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "ccd.decentralised-runtime.cdam-attach", name = "enabled", havingValue = "true")
public class CdamAttachService {

  private final CaseDocumentHashScanner scanner;
  private final CaseDocumentClient caseDocumentClient;
  private final AuthTokenGenerator authTokenGenerator;

  public CdamAttachService(CaseDocumentHashScanner scanner,
                           CaseDocumentClient caseDocumentClient,
                           AuthTokenGenerator authTokenGenerator) {
    this.scanner = scanner;
    this.caseDocumentClient = caseDocumentClient;
    this.authTokenGenerator = authTokenGenerator;
  }

  public JsonNode attachNewDocumentsAndStripHashes(String authorisation,
                                                   DecentralisedCaseEvent event,
                                                   JsonNode preCallbackData,
                                                   JsonNode postCallbackData) {
    var tokens = scanner.findNewDocumentHashTokens(preCallbackData, postCallbackData);
    JsonNode strippedData = scanner.stripDocumentHashes(postCallbackData);

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
      caseDocumentClient.patchDocument(authorisation, authTokenGenerator.generate(), metadata);
      log.info("Attached CDAM documents caseId={} caseType={} jurisdiction={} documentCount={}",
          metadata.getCaseId(), metadata.getCaseTypeId(), metadata.getJurisdictionId(), tokens.size());
      return strippedData;
    } catch (RuntimeException ex) {
      throw new CdamAttachException(
          "Unable to attach CDAM documents caseId=%s caseType=%s jurisdiction=%s documentCount=%d".formatted(
              metadata.getCaseId(), metadata.getCaseTypeId(), metadata.getJurisdictionId(), tokens.size()),
          ex
      );
    }
  }
}
