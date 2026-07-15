package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.type.FieldType.WaysToPay;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.JudicialUser;

/**
 * Exercises two field-type additions: {@code @CCD(typeOverride = WaysToPay)} on a plain
 * {@code String} (see {@link uk.gov.hmcts.ccd.sdk.type.FieldType}), and the predefined complex
 * type {@link JudicialUser}, whose class name resolves to the {@code JudicialUser} FieldType
 * without needing a typeOverride.
 */
@Data
public class FieldTypeCompletionCaseData {

  @CCD(label = "Service request", typeOverride = WaysToPay)
  private String serviceRequest;

  @CCD(label = "Allocated judge")
  private JudicialUser allocatedJudge;
}
