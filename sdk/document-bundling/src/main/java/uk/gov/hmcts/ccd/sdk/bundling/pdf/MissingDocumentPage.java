package uk.gov.hmcts.ccd.sdk.bundling.pdf;

/** A generated page standing in for a document that could not be included. */
public record MissingDocumentPage(String reason) implements AssemblyContent {
  public MissingDocumentPage {
    Checks.requireNonBlank(reason, "MissingDocumentPage.reason");
  }
}
