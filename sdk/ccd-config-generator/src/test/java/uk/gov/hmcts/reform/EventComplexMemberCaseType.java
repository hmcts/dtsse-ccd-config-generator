package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.EventComplexMemberState.Open;
import static uk.gov.hmcts.reform.EventComplexMemberState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type that overrides members of a complex field within an event, carrying per-member
 * {@code EventToComplexTypes} labels, hints and show conditions. It exercises the fluent
 * {@code .eventLabel(String)}/{@code .eventHint(String)} members alongside the existing positional
 * label/hint arguments and a nested-member override, so the golden snapshot pins the four
 * high-frequency columns (EventElementLabel, EventHintText, FieldShowCondition, plus the dotted
 * ListElementCode of a nested member).
 */
@Component
public class EventComplexMemberCaseType
    implements CCDConfig<EventComplexMemberCaseData, EventComplexMemberState, UserRole> {

  @Override
  public void configure(
      ConfigBuilder<EventComplexMemberCaseData, EventComplexMemberState, UserRole> builder) {
    builder.caseType(
        "EventComplexMember", "Event complex member", "Per-event complex-type member overrides");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .complex(EventComplexMemberCaseData::getContact)
          // Fluent label only.
          .mandatory(EventComplexMemberContact::getName)
            .eventLabel("Your full name")
          // Fluent label + hint, chained after a show condition.
          .optional(EventComplexMemberContact::getEmail, "contactName=\"*\"")
            .eventLabel("Your email")
            .eventHint("We only use this to contact you")
          // A read-only member carrying a fluent label and hint — the positional readonly()
          // overloads offer no label/hint arguments, so .eventLabel/.eventHint are the only route.
          .readonly(EventComplexMemberContact::getReference)
            .eventLabel("Your reference")
            .eventHint("Shown for reference only")
          // Nested member override with a dotted ListElementCode plus a per-member PageID.
          .complex(EventComplexMemberContact::getAddress)
            .optional(EventComplexMemberNested::getPostcode)
              .eventLabel("Postcode")
              .pageId("2")
            .done()
          .done();
  }
}
