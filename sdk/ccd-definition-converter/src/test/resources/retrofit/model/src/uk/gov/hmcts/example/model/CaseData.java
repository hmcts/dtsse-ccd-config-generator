package uk.gov.hmcts.example.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.LocalDate;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.example.enums.ClaimType;
import uk.gov.hmcts.example.model.common.DocItem;
import uk.gov.hmcts.example.model.common.ListValue;
import uk.gov.hmcts.example.model.common.Party;
import uk.gov.hmcts.example.model.event.HearingEventData;
import uk.gov.hmcts.example.model.event.ConfidentialData;

/**
 * A hand-written fake CaseData exercising every retrofit resolver rule. Parsed as source (never
 * compiled); the imported SDK/enum types resolve by name where present in the fake tree.
 */
public class CaseData extends BaseCaseData {

  // Rule 1: plain field name -> id "applicantName", type String -> Text (EXACT).
  private String applicantName;

  // Rule 1: LocalDate -> Date (EXACT).
  private LocalDate dateOfBirth;

  // Rule 2: @JsonProperty overrides the field name -> id "renamedId".
  @JsonProperty("renamedId")
  private String someInternalName;

  // Rule: enum -> FixedRadioList. Definition declares FixedList for it (TYPE conflict is allowed to
  // be EXACT because both list flavours are reachable from an enum via override).
  private ClaimType claimType;

  // Rule: generic wrapper collection List<ListValue<Party>> -> Collection of Party (EXACT when the
  // definition's FieldTypeParameter is Party).
  private List<ListValue<Party>> parties;

  // Rule: concrete value-wrapper collection List<DocItem> -> the SDK would emit DocItem, but the
  // definition wants the inner "Document" -> concrete-wrapper TYPE_CONFLICT.
  private List<DocItem> documents;

  // Rule 5: @JsonIgnore excludes the field entirely (never a definition field).
  @JsonIgnore
  private String internalCache;

  // Rule 5: @CCD(ignore = true) excludes the field entirely.
  @CCD(ignore = true)
  private String auditOnly;

  // Rule: a model field with no matching definition ID -> UNMATCHED_JAVA_FIELD.
  private String orphanModelField;

  // Rule 3: prefixed @JsonUnwrapped -> ids "hearingType", "hearingLength" (prefix + capitalize).
  @JsonUnwrapped(prefix = "hearing")
  private HearingEventData hearingEventData;

  // Rule 3: prefix-less @JsonUnwrapped -> ids emitted verbatim ("confidentialNote").
  @JsonUnwrapped
  private ConfidentialData confidentialData;

  // Static fields are excluded (Jackson never serialises them).
  public static final String CONSTANT = "x";
}
