package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link EventComplexScopeCaseType}. {@link #appeal} is a SCALAR complex field placed
 * on the event as {@code READONLY} while still carrying per-member {@code CaseEventToComplexTypes}
 * overrides — the shape {@code .complexScope(getter)} exists for, since {@code .complex(getter)}
 * would force the field's own row to {@code DisplayContext=COMPLEX}.
 *
 * <p>{@link #jointParty} is the same members-of-a-complex-type story on the other sheet: held
 * {@code @JsonUnwrapped} with no prefix, its members are top-level fields, so their placements emit
 * {@code CaseEventToFields} rows — where {@code ShowSummaryChangeOption} exists and the
 * {@code complexMember}/{@code complexMemberNoSummary} pair is therefore distinguishable.
 */
@Data
public class EventComplexScopeCaseData {

  @CCD(label = "Appeal details", access = {SolicitorAccess.class})
  private EventComplexScopeAppeal appeal;

  @JsonUnwrapped
  @CCD(access = {SolicitorAccess.class})
  private EventComplexScopeJointParty jointParty;
}
