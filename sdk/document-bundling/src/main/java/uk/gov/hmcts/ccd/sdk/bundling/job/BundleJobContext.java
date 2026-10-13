package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;

/**
 * What a document selector sees when a claimed job executes: the request as submitted (empty
 * for a selector-parameters submission), the selector parameters, and the execution context.
 */
public record BundleJobContext(
    UUID externalId,
    Optional<BundleRequest> submittedRequest,
    Map<String, String> parameters,
    BundleExecutionContext executionContext) {

  public BundleJobContext {
    Objects.requireNonNull(externalId, "BundleJobContext.externalId");
    Objects.requireNonNull(submittedRequest, "BundleJobContext.submittedRequest");
    Objects.requireNonNull(executionContext, "BundleJobContext.executionContext");
    parameters = Map.copyOf(parameters);
  }
}
