package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.type.FieldType.RichTextArea;
import static uk.gov.hmcts.ccd.sdk.type.FieldType.WaysToPay;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.CaseQueriesCollection;
import uk.gov.hmcts.ccd.sdk.type.JudicialUser;

/**
 * Exercises field-type additions: {@code @CCD(typeOverride = WaysToPay)} and
 * {@code @CCD(typeOverride = RichTextArea)} on a plain
 * {@code String} (see {@link uk.gov.hmcts.ccd.sdk.type.FieldType}), and the predefined complex
 * types {@link JudicialUser} and {@link CaseQueriesCollection}, whose class names resolve to
 * their respective FieldTypes without needing a typeOverride.
 */
@Data
public class FieldTypeCompletionCaseData {

  @CCD(label = "Service request", typeOverride = WaysToPay)
  private String serviceRequest;

  @CCD(label = "Rich text notes", typeOverride = RichTextArea)
  private String richTextNotes;

  @CCD(label = "Allocated judge")
  private JudicialUser allocatedJudge;

  @CCD(label = "Queries")
  private CaseQueriesCollection queries;
}
