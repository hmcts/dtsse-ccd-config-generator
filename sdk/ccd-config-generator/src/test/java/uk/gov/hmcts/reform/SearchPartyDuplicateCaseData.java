package uk.gov.hmcts.reform;

import uk.gov.hmcts.ccd.sdk.api.CCD;

/**
 * Data for {@link SearchPartyDuplicateCaseType}. Only a placeholder field is needed — the case type
 * exercises the {@code SearchParty} sheet, which is keyed on party/collection-field metadata rather
 * than on this case data.
 */
public class SearchPartyDuplicateCaseData {

  @CCD(label = "A field")
  private String aField;
}
