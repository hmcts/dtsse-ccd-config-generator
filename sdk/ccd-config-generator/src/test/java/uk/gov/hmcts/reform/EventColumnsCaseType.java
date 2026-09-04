package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.EventColumnsState.Closed;
import static uk.gov.hmcts.reform.EventColumnsState.Open;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * Exercises three small, independently-optional column-graft replacements:
 * {@link uk.gov.hmcts.ccd.sdk.api.Event.EventBuilder#significant()} (CaseEvent
 * {@code SignificantEvent}), {@link ConfigBuilder#enableForDeletion()} (CaseType
 * {@code EnableForDeletion}), and {@link ConfigBuilder#jurisdictionShuttered()} (Jurisdiction
 * {@code Shuttered}). Each is default-off; a config that does not call them produces no trace of
 * the corresponding column.
 */
@Component
public class EventColumnsCaseType
    implements CCDConfig<EventColumnsCaseData, EventColumnsState, UserRole> {

  @Override
  public void configure(ConfigBuilder<EventColumnsCaseData, EventColumnsState, UserRole> builder) {
    builder.caseType("EventColumns", "Event columns", "Event columns case type");
    builder.jurisdiction("EVENTCOLUMNS", "Event columns jurisdiction", "Event columns jurisdiction desc");
    builder.enableForDeletion();
    builder.jurisdictionShuttered();

    builder.event("close")
        .forStateTransition(Open, Closed)
        .name("Close")
        .significant()
        .grant(CRU, LOCAL_AUTHORITY);
  }
}
