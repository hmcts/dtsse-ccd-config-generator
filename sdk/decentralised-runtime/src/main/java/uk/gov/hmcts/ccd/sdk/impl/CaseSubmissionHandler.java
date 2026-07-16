package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.function.Supplier;
import uk.gov.hmcts.ccd.decentralised.dto.DecentralisedCaseEvent;
import uk.gov.hmcts.ccd.sdk.api.EventMetadata;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.SignificantItem;

/**
 * Defines a single submission flow. Implementations decide how to apply an event
 * (legacy callbacks vs submit handlers) but leave orchestration to the caller.
 */
interface CaseSubmissionHandler {

  CaseSubmissionHandlerResult apply(DecentralisedCaseEvent event, String authorisation);

  /**
   * Result returned by a submission handler after it has prepared all in-transaction mutations.
   *
   * <p>The {@code responseSupplier} is intentionally deferred and is invoked by
   * {@link CaseSubmissionService} only after the database transaction has completed successfully.
   * Use it for post-transaction response assembly such as submitted/post-submit callbacks,
   * confirmation header/body population, or other side-effecting response data.
   */
  record CaseSubmissionHandlerResult(
      /*
       * Optional replacement payload for the non-reserved portion of {@code ccd.case_data.data}.
       * If empty, that portion of the legacy JSON blob is unchanged.
       */
      Optional<JsonNode> dataUpdate,
      /*
       * Optional post-submit canonical case state to be persisted.
       */
      Optional<String> state,
      /*
       * Optional post-submit canonical case level security classification to be persisted.
       */
      Optional<uk.gov.hmcts.reform.ccd.client.model.Classification> securityClassification,
      /*
       * Optional replacement values for the case event audit metadata.
       */
      Optional<EventMetadata> eventMetadata,
      /*
       * Optional CCD significant item to associate with the created audit event.
       */
      Optional<SignificantItem> significantItem,
      /*
       * Deferred response builder executed post-transaction by {@link CaseSubmissionService}.
       */
      Supplier<SubmitResponse<?>> responseSupplier) {
  }
}
