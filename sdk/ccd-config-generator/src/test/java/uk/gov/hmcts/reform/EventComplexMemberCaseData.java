package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link EventComplexMemberCaseType}. {@link #contact} is a complex field whose
 * members are overridden — with fluent per-event labels, hints and show conditions — inside the
 * event's field collection.
 */
@Data
public class EventComplexMemberCaseData {

  @CCD(label = "Contact details", access = {SolicitorAccess.class})
  private EventComplexMemberContact contact;
}
