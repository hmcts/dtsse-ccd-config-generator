package uk.gov.hmcts.reform;

import java.util.List;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link EventComplexCollectionCaseType}. {@link #parties} is a {@code Collection}
 * field whose element members are overridden per event via the element-typed
 * {@code .complex(getter, Class)} scope — placed once as a top-level {@code .optional(getter)} and
 * scoped separately, so opening the scope must not touch the collection field's own
 * {@code CaseEventToFields} row.
 */
@Data
public class EventComplexCollectionCaseData {

  @CCD(label = "Parties", access = {SolicitorAccess.class})
  private List<ListValue<EventComplexCollectionParty>> parties;
}
