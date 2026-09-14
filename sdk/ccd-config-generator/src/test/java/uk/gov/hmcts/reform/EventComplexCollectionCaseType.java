package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.EventComplexCollectionState.Open;
import static uk.gov.hmcts.reform.EventComplexCollectionState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type that overrides the element members of a {@code Collection} field within an event,
 * exercising both new SDK affordances:
 *
 * <ul>
 *   <li>the element-typed {@code .complex(getter, Class)} scope, opened on a
 *   {@code List<ListValue<U>>} collection field <em>without</em> registering the collection field as
 *   a root — the field is placed separately as a top-level {@code .optional(getter)}, so the golden
 *   {@code CaseEventToFields} row for {@code parties} must be untouched by opening the scope — and
 *   the same overload again on a nested collection ({@code children}) to prove it composes;</li>
 *   <li>the tri-state {@code CaseEventToComplexTypes.HintText} carrier: {@code partyName} overrides
 *   its (absent) declared hint with {@code .hintText(...)}, {@code role} leaves its declared hint to
 *   cascade (unset), and {@code reference} suppresses its declared hint with {@code .noHintText()}.
 *   {@code children.childName} leaves its declared hint to cascade through the nested scope.</li>
 * </ul>
 */
@Component
public class EventComplexCollectionCaseType
    implements CCDConfig<EventComplexCollectionCaseData, EventComplexCollectionState, UserRole> {

  @Override
  public void configure(
      ConfigBuilder<EventComplexCollectionCaseData, EventComplexCollectionState, UserRole> builder) {
    builder.caseType(
        "EventComplexCollection", "Event complex collection",
        "Per-event complex-collection element overrides");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        // The collection field itself is placed once, as an ordinary top-level field; opening the
        // element scope below must not add a second field row or alter this one.
        .optional(EventComplexCollectionCaseData::getParties)
        // Element-typed scope on the collection: no root field is registered here.
        .complex(EventComplexCollectionCaseData::getParties, EventComplexCollectionParty.class)
          // HintText override: partyName declares no @CCD(hint), so .hintText adds one.
          .mandatory(EventComplexCollectionParty::getPartyName)
            .hintText("An overriding hint")
          // HintText unset: role's declared @CCD(hint) cascades onto the row unchanged.
          .optional(EventComplexCollectionParty::getRole)
          // HintText suppressed: reference declares a hint, but .noHintText drops the column.
          .readonly(EventComplexCollectionParty::getReference)
            .noHintText()
          // Nested collection scope, opened with the same element-typed overload on the nested
          // builder; childName's declared hint cascades through it.
          .complex(EventComplexCollectionParty::getChildren, EventComplexCollectionChild.class)
            .mandatory(EventComplexCollectionChild::getChildName)
          .done()
        .done();
  }
}
