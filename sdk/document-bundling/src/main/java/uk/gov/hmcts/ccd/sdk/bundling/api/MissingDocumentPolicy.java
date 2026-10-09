package uk.gov.hmcts.ccd.sdk.bundling.api;

/**
 * What a render does with a document it cannot include: one that cannot be fetched, converted
 * or read.
 */
public enum MissingDocumentPolicy {

  /** Fail the whole bundle, naming every document that could not be included. The default. */
  FAIL,

  /**
   * Include a placeholder page in the document's place, naming it and why it is missing, and
   * report it in {@link BundleResult#missingDocuments()}. A source that is only temporarily
   * unavailable still fails the attempt while a durable job has retries left, so a brief outage
   * does not produce an incomplete bundle.
   */
  PLACEHOLDER
}
