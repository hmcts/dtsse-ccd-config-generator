package uk.gov.hmcts.ccd.sdk;

import java.util.UUID;

public interface SystemCaseEventService {

  <T, S> SystemCaseEventResult submit(
      long caseReference,
      SystemCaseEvent event,
      UUID idempotencyKey,
      SystemCaseEventAction<T, S> action
  );

  <T, S> SystemCaseEventResult submitOnBehalfOf(
      long caseReference,
      SystemCaseEvent event,
      UUID idempotencyKey,
      SystemCaseEventActor actor,
      SystemCaseEventAction<T, S> action
  );
}
