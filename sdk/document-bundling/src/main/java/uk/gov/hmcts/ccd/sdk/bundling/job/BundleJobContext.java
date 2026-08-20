package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/**
 * What a {@link BundleDocumentSelector} sees when a claimed job executes.
 *
 * @param externalId the job's idempotency key
 * @param submittedRequest the full request, present when the job was submitted with one
 *     (snapshot-at-submission); empty when the consumer submits only selector parameters
 * @param parameters the consumer's selector parameters, for example a case reference or hearing
 *     id, compiled into a request at execution time
 * @param executionContext the consumer context passed through to resolvers and the destination
 */
public record BundleJobContext(
    UUID externalId,
    Optional<BundleRequest> submittedRequest,
    Map<String, String> parameters,
    BundleExecutionContext executionContext) {

  public BundleJobContext {
    requireNonNull(externalId, "externalId");
    requireNonNull(submittedRequest, "submittedRequest");
    requireNonNull(executionContext, "executionContext");
    parameters = Map.copyOf(parameters);
  }

  private static void requireNonNull(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException("BundleJobContext." + field + " must be provided");
    }
  }
}
