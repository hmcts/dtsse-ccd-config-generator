package uk.gov.hmcts.ccd.sdk.testing;

import feign.Capability;
import feign.Client;

/**
 * Applied by Spring Cloud OpenFeign to every Feign client, so an application's own CDAM client
 * finds the documents a test stored without the application faking it.
 */
public final class TestCaseDocumentCapability implements Capability {

  private final TestDocuments documents;

  TestCaseDocumentCapability(TestDocuments documents) {
    this.documents = documents;
  }

  @Override
  public Client enrich(Client client) {
    return new TestCaseDocumentClient(client, documents);
  }
}
