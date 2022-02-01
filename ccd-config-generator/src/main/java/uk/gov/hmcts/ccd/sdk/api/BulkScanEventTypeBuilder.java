package uk.gov.hmcts.ccd.sdk.api;

import java.util.EnumSet;

public interface BulkScanEventTypeBuilder<T, R extends HasRole, S> {
  Event.EventBuilder<T, R, S> forAllStates();

  Event.EventBuilder<T, R, S> forStateTransition(EnumSet from, S to);
}
