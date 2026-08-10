package uk.gov.hmcts.example.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.LocalDate;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.example.enums.AnnotatedList;
import uk.gov.hmcts.example.enums.CamelConstantList;
import uk.gov.hmcts.example.enums.ClaimType;
import uk.gov.hmcts.example.enums.LabelBearingList;
import uk.gov.hmcts.example.enums.SharedLineList;
import uk.gov.hmcts.example.enums.ShadowedPinList;
import uk.gov.hmcts.example.enums.State;
import uk.gov.hmcts.example.enums.TeamOwnVocabulary;
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

  // Refusal: implements HasLabel, which FixedListGenerator reads before @CCD(label), so no pin.
  private LabelBearingList labelBearing;

  // Refusal: its constant already carries a team-written @CCD, which the pin must not duplicate.
  private AnnotatedList annotated;

  // The pin must split its shared constant line before annotating (@CCD is not @Repeatable).
  private SharedLineList sharedLine;

  // The pin must match rows on the raw ListElementCode too, not just the sanitised constant.
  private CamelConstantList camelConstant;

  // The label pin must read the code the constant REALLY emits: this enum carries a per-constant
  // @JsonProperty that its own @JsonValue overrides (prl's DocumentPartyEnum), so the emitted code is
  // COURT and the definition's "Court" label still needs pinning.
  private ShadowedPinList shadowedPin;

  // A team enum declaring MORE constants than the definition's list has codes (sscs's EventType, 261
  // against 15). FixedListGenerator emits one row per constant with no filter, so pinning the list's ID
  // onto this enum turns a list with no rows into a list with WRONG rows. The binding must be refused
  // and the field pointed at the companion the refusal leaves in place instead.
  private TeamOwnVocabulary oversized;

  // A large reference-data FixedList the team really models as a String (sscs's hearingEpimsId, 160-odd
  // venue codes loaded at runtime): nothing DECLARES the list's type, so reflection reaches no rows for
  // it and the typeParameterOverride alone names a list the SDK never emits. The patch must name the
  // generated companion with @CCD(typeParameterClass) rather than retype the field.
  private String hearingVenue;

  // The State enum declared as an ordinary field, as sscs really does: reflection then reaches it as a
  // fixed list the definition also has rows for, so the State-label pin and the FixedLists-label pin
  // both want its constants and precedence decides (State wins).
  private State state;

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
