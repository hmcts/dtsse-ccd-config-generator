package uk.gov.hmcts.ccd.sdk.impl.cdam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

@Component
@ConditionalOnProperty(prefix = "ccd.decentralised-runtime.cdam-attach", name = "enabled", havingValue = "true")
public class CaseDocumentAmClient {

  private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";
  private static final String ATTACH_TO_CASE_PATH = "/cases/documents/attachToCase";

  private final RestClient restClient;
  private final AuthTokenGenerator authTokenGenerator;

  public CaseDocumentAmClient(RestClient.Builder restClientBuilder,
                              AuthTokenGenerator authTokenGenerator,
                              @Value("${case_document_am.url}") String caseDocumentAmUrl) {
    this.restClient = restClientBuilder.baseUrl(caseDocumentAmUrl).build();
    this.authTokenGenerator = authTokenGenerator;
  }

  public void attach(String authorisation, CaseDocumentsMetadata metadata) {
    restClient.patch()
        .uri(ATTACH_TO_CASE_PATH)
        .header(HttpHeaders.AUTHORIZATION, authorisation)
        .header(SERVICE_AUTHORIZATION, authTokenGenerator.generate())
        .contentType(MediaType.APPLICATION_JSON)
        .body(metadata)
        .retrieve()
        .toBodilessEntity();
  }
}
