package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link EventComplexScopeCaseType}. {@link #appeal} is a SCALAR complex field placed
 * on the event as {@code READONLY} while still carrying per-member {@code CaseEventToComplexTypes}
 * overrides — the shape {@code .complexScope(getter)} exists for, since {@code .complex(getter)}
 * would force the field's own row to {@code DisplayContext=COMPLEX}.
 */
@Data
public class EventComplexScopeCaseData {

  @CCD(label = "Appeal details", access = {SolicitorAccess.class})
  private EventComplexScopeAppeal appeal;
}
