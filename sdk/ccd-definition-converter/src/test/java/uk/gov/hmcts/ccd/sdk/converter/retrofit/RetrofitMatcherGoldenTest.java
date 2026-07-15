package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;

/**
 * Pins the retrofit resolver against a small hand-written fake model tree
 * ({@code src/test/resources/retrofit/model}) whose fields exercise every resolution rule
 * (field name, {@code @JsonProperty}, prefixed and prefix-less {@code @JsonUnwrapped}, superclass
 * fields, {@code @JsonIgnore}/{@code @CCD(ignore)} exclusion, generic vs concrete collection
 * wrappers, enum FixedRadioList, state-ID derivation) and a tiny definition. Runs in the fast
 * {@code check} task — no model compilation, source parsing only.
 */
class RetrofitMatcherGoldenTest {

  private static final Path MODEL_ROOT =
      Path.of("src/test/resources/retrofit/model/src").toAbsolutePath();
  private static final Path MAP_MODEL_ROOT =
      Path.of("src/test/resources/retrofit/mapmodel/src").toAbsolutePath();
  private static final Path DEFINITION =
      Path.of("src/test/resources/retrofit/definition").toAbsolutePath();

  private RetrofitReport run(Path modelRoot, String modelPackage, String modelClass) {
    Map<String, OverlayCondition> overlays = new LinkedHashMap<>();
    overlays.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    overlays.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
    ConversionOptions options = ConversionOptions.builder()
        .inputs(java.util.List.of(DEFINITION))
        .caseTypeId("EXAMPLE")
        .modelPackage(modelPackage)
        .overlaySuffixes(overlays)
        .build();
    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    return new RetrofitMatcher(ir, "EXAMPLE", modelRoot, modelPackage, modelClass).match();
  }

  private Map<String, RetrofitReport.FieldFinding> byId(RetrofitReport report) {
    return report.getFields().stream()
        .collect(Collectors.toMap(RetrofitReport.FieldFinding::getId, Function.identity()));
  }

  @Test
  void totalsCoverEveryBucket() {
    RetrofitReport report = run(MODEL_ROOT, "uk.gov.hmcts.example.model", "CaseData");

    assertThat(report.isMapBased()).isFalse();
    // 13 CaseField rows, 1 is a Label -> 12 data-bearing.
    assertThat(report.getTotalDefinitionFields()).isEqualTo(13);
    assertThat(report.getLabelFields()).isEqualTo(1);
    assertThat(report.getDataBearingFields()).isEqualTo(12);

    // 8 exact, 2 type-conflict (documents concrete wrapper AND dateOfBirth date/dateTime),
    // 2 unmatched (extraSynthField + confidentialData, which collides with the @JsonUnwrapped parent).
    assertThat(report.getUnmatchedDefinitionFields()).isEqualTo(2);
    assertThat(report.getTypeConflicts()).isEqualTo(2);
    assertThat(report.getExactMatches()).isEqualTo(8);
    assertThat(report.getExactMatches() + report.getTypeConflicts()
        + report.getUnmatchedDefinitionFields()).isEqualTo(12);
  }

  @Test
  void resolvesEveryFieldRule() {
    Map<String, RetrofitReport.FieldFinding> fields =
        byId(run(MODEL_ROOT, "uk.gov.hmcts.example.model", "CaseData"));

    // Rule 1: plain field name.
    assertExact(fields, "applicantName", "CaseData", "applicantName", "Text");
    // Rule 4: superclass field.
    assertExact(fields, "caseReference", "BaseCaseData", "caseReference", "Text");
    // Rule 2: @JsonProperty override (member someInternalName -> id renamedId).
    assertExact(fields, "renamedId", "CaseData", "someInternalName", "Text");
    // Enum -> FixedRadioList, compatible with a FixedList definition.
    assertExact(fields, "claimType", "CaseData", "claimType", "FixedRadioList");
    // Generic wrapper collection descends to Party.
    assertExact(fields, "parties", "CaseData", "parties", "Collection");
    // Rule 3: prefixed @JsonUnwrapped -> hearingType / hearingLength.
    assertExact(fields, "hearingType", "HearingEventData", "type", "Text");
    assertExact(fields, "hearingLength", "HearingEventData", "length", "Number");
    // Rule 3: prefix-less @JsonUnwrapped -> confidentialNote verbatim.
    assertExact(fields, "confidentialNote", "ConfidentialData", "confidentialNote", "Text");

    // Concrete wrapper collection: TYPE_CONFLICT flagged as concreteWrapper with a
    // typeParameterOverride action.
    RetrofitReport.FieldFinding documents = fields.get("documents");
    assertThat(documents.getBucket()).isEqualTo(RetrofitReport.Bucket.TYPE_CONFLICT);
    assertThat(documents.isConcreteWrapper()).isTrue();
    assertThat(documents.getInferredFieldType()).isEqualTo("Collection");
    assertThat(documents.getAction()).contains("typeParameterOverride");

    // Plain (non-wrapper) TYPE_CONFLICT: model LocalDate infers Date, definition says DateTime.
    // DateTime is NOT a FieldType enum constant, so @CCD(typeOverride = FieldType.DateTime) would not
    // compile — the action must say so honestly rather than recommend uncompilable code (bug A1: the
    // phase-2 emitter already drops it; phase-1 was still suggesting it).
    RetrofitReport.FieldFinding dob = fields.get("dateOfBirth");
    assertThat(dob.getBucket()).isEqualTo(RetrofitReport.Bucket.TYPE_CONFLICT);
    assertThat(dob.isConcreteWrapper()).isFalse();
    assertThat(dob.getInferredFieldType()).isEqualTo("Date");
    assertThat(dob.getAction())
        .doesNotContain("typeOverride = FieldType.DateTime")
        .contains("genuine type divergence")
        .contains("reconcile the model type by hand");

    // Unmatched definition field.
    RetrofitReport.FieldFinding extra = fields.get("extraSynthField");
    assertThat(extra.getBucket()).isEqualTo(RetrofitReport.Bucket.UNMATCHED_DEFINITION_FIELD);
    assertThat(extra.getAction()).contains("synthesise");

    // @JsonIgnore / @CCD(ignore) / static / Label members never become definition matches.
    assertThat(fields).doesNotContainKeys("internalCache", "auditOnly", "CONSTANT", "sectionLabel");
  }

  @Test
  void excludesIgnoredAndCountsAnnotations() {
    RetrofitReport report = run(MODEL_ROOT, "uk.gov.hmcts.example.model", "CaseData");

    // internalCache (@JsonIgnore) + auditOnly (@CCD(ignore=true)).
    assertThat(report.getJsonIgnoreCount()).isEqualTo(2);
    // hearingEventData (prefixed) + confidentialData (prefix-less).
    assertThat(report.getJsonUnwrappedCount()).isEqualTo(2);
    assertThat(report.getPrefixlessJsonUnwrappedCount()).isEqualTo(1);
    // BaseCaseData.
    assertThat(report.getSuperclassCount()).isEqualTo(1);
  }

  @Test
  void listsUnmatchedJavaFields() {
    RetrofitReport report = run(MODEL_ROOT, "uk.gov.hmcts.example.model", "CaseData");
    assertThat(report.getUnmatchedJavaFields())
        .extracting(RetrofitReport.UnmatchedJavaField::getId)
        .contains("orphanModelField");
  }

  @Test
  void surveysCollectionWrappers() {
    RetrofitReport report = run(MODEL_ROOT, "uk.gov.hmcts.example.model", "CaseData");
    RetrofitReport.CollectionSurvey survey = report.getCollectionSurvey();
    assertThat(survey.getTotalCollectionFields()).isEqualTo(2);
    assertThat(survey.getGenericWrapperFields()).isEqualTo(1);
    assertThat(survey.getConcreteWrapperFields()).isEqualTo(1);
    assertThat(survey.getConcreteWrapperTypes()).contains("DocItem");
  }

  @Test
  void derivesStateIdsHonouringJsonProperty() {
    RetrofitReport.StateVerdict verdict =
        run(MODEL_ROOT, "uk.gov.hmcts.example.model", "CaseData").getStateVerdict();
    assertThat(verdict.isStateEnumFound()).isTrue();
    assertThat(verdict.getDefinitionStates()).isEqualTo(4);
    // Open, PREPARE_FOR_HEARING (via @JsonProperty on CASE_MANAGEMENT), CLOSED match; Withdrawn does not.
    assertThat(verdict.getMatchedStates()).isEqualTo(3);
    assertThat(verdict.getConflictingStates()).isEqualTo(1);
    assertThat(verdict.getConflicts()).containsExactly("Withdrawn");
  }

  @Test
  void detectsMapBasedModel() {
    RetrofitReport report = run(MAP_MODEL_ROOT, "uk.gov.hmcts.example.map", "AsylumCase");
    assertThat(report.isMapBased()).isTrue();
    assertThat(report.getNotApplicableReason()).contains("Map/HashMap").contains("generate mode");
  }

  @Test
  void rendersMarkdownAndJsonSummary() {
    RetrofitReport report = run(MODEL_ROOT, "uk.gov.hmcts.example.model", "CaseData");
    String md = RetrofitReportWriter.renderMarkdown(report);
    assertThat(md).contains("# CCD Retrofit Match Report")
        .contains("EXACT_MATCH")
        .contains("TYPE_CONFLICT")
        .contains("Collection-wrapper survey")
        .contains("State enum");
    assertThat(RetrofitReportWriter.summaryMap(report)).containsEntry("mapBased", false);
  }

  private void assertExact(Map<String, RetrofitReport.FieldFinding> fields, String id,
      String modelClass, String member, String inferred) {
    RetrofitReport.FieldFinding f = fields.get(id);
    assertThat(f).as("field %s", id).isNotNull();
    assertThat(f.getBucket()).as("bucket of %s", id).isEqualTo(RetrofitReport.Bucket.EXACT_MATCH);
    assertThat(f.getModelClass()).as("owner of %s", id).isEqualTo(modelClass);
    assertThat(f.getMemberName()).as("member of %s", id).isEqualTo(member);
    assertThat(f.getInferredFieldType()).as("inferred of %s", id).isEqualTo(inferred);
  }
}
