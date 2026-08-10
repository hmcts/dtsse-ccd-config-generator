package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.EventComplexScopeState.Open;
import static uk.gov.hmcts.reform.EventComplexScopeState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type overriding the members of a SCALAR complex field placed with a non-{@code COMPLEX}
 * {@code DisplayContext}.
 *
 * <p>It exercises the two affordances that decouple a member scope from the placement that opens it:
 *
 * <ul>
 *   <li>{@code .complexScope(getter)} — the scalar analogue of the element-typed
 *   {@code .complex(getter, Class)} scope proven by {@link EventComplexCollectionCaseType}. The
 *   {@code appeal} field is placed once as {@code .readonly(getter)} and scoped separately, so its
 *   own {@code CaseEventToFields} row must stay {@code READONLY} — a bare {@code .complex(getter)}
 *   would have forced it to {@code COMPLEX}. This is sscs's
 *   {@code updateOtherPartyData/appeal} shape.</li>
 *   <li>{@code .complexMember(getter)} — a member placed as {@code DisplayContext=COMPLEX} in its own
 *   right. {@code benefitType} gets a {@code COMPLEX} row AND is descended into for
 *   {@code benefitType.code}, proving the two compose on one intermediate (sscs's
 *   {@code confirmPoAttendance/presentingOfficersDetails} ships {@code contact.*} this way).</li>
 * </ul>
 */
@Component
public class EventComplexScopeCaseType
    implements CCDConfig<EventComplexScopeCaseData, EventComplexScopeState, UserRole> {

  @Override
  public void configure(
      ConfigBuilder<EventComplexScopeCaseData, EventComplexScopeState, UserRole> builder) {
    builder.caseType(
        "EventComplexScope", "Event complex scope",
        "Per-event member overrides on a non-COMPLEX-placed scalar complex field");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        // The complex field itself, placed with the context the definition asks for. Opening the
        // member scope below must neither add a second row nor change this one to COMPLEX.
        .readonly(EventComplexScopeCaseData::getAppeal)
        // Scalar member scope: registers no root field.
        .complexScope(EventComplexScopeCaseData::getAppeal)
          // The intermediate placed as a COMPLEX member row in its own right...
          .complexMember(EventComplexScopeAppeal::getBenefitType)
          // ...and descended into for its leaf, which emits the dotted ListElementCode.
          .complex(EventComplexScopeAppeal::getBenefitType)
            .mandatory(EventComplexScopeBenefit::getCode)
          .done()
          .optional(EventComplexScopeAppeal::getReference)
        .done();
  }
}
