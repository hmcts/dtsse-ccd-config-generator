package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.reader.JsonDefinitionReader;

/**
 * The proposal's phase-1 acceptance: run the retrofit matcher against the four real POJO archetypes
 * (FPL, ET, Civil, SSCS) and confirm the measured match rate lands in a sane band, plus confirm the
 * IA-style map-based model reports "not applicable".
 *
 * <p>Each fixture case is guarded with a JUnit assumption on submodule presence (the
 * {@code RoundTripTest} pattern) so a checkout without the submodules skips rather than fails.
 * Tagged {@code round-trip} to keep the default {@code check} fast — the fixtures are large model
 * trees (Civil parses 3500+ files) and are submodule-gated exactly like the round-trip fixtures.
 *
 * <p><b>Bands and the proposal's hand floors.</b> The proposal's per-archetype floors (FPL 28 %,
 * ET 98 %, Civil 94 %, SSCS 94 %) were measured by <em>exact top-level name match only</em> — the
 * hand count did not walk {@code @JsonUnwrapped} clusters or superclasses (§1a lists those as
 * reasons the real matcher would exceed the floor). This resolver <em>does</em> walk them, so its
 * <b>resolved rate</b> (name resolution = EXACT + TYPE_CONFLICT) reproduces ET/Civil/SSCS almost
 * exactly (their models are already flat, field-per-CaseField) but lifts FPL far above its 28 %
 * floor (FPL's 30 prefix-less event-data unwraps + a {@code CaseDataParent} superclass recover
 * hundreds of IDs the top-level-only count missed). These measured floors supersede the hand
 * floors in the proposal.
 */
@Tag("round-trip")
class RetrofitFixtureMatchTest {

  private static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

  @Test
  void fplResolvesWellAboveItsNameOnlyFloor() {
    // Proposal hand floor 28 % (top-level names only). With @JsonUnwrapped + superclass walking the
    // resolver recovers ~71 %. Assert comfortably above the hand floor and in the measured band.
    RetrofitReport report = matchFpl();
    assumeTrue(report != null, "fpl-ccd-configuration submodule not initialised; skipping");
    assertThat(report.isMapBased()).isFalse();
    assertThat(report.getDataBearingFields()).isGreaterThan(1300);
    assertThat(report.resolvedPercent()).isBetween(65.0, 78.0);
    // The State enum reconciles via @JsonProperty on its constants (decision 3 / StateId), so it is
    // reusable — the proposal's pre-decision-3 "≥5/9 conflict" no longer holds.
    assertThat(report.getStateVerdict().isStateEnumFound()).isTrue();
    assertThat(report.getStateVerdict().getConflictingStates()).isZero();
  }

  @Test
  void etMatchesNearlyCompletely() {
    RetrofitReport report = matchEt();
    assumeTrue(report != null, "et-ccd-callbacks model not present; skipping");
    assertThat(report.isMapBased()).isFalse();
    assertThat(report.resolvedPercent()).isBetween(96.0, 100.0);
    // ET has no State enum/field — the FPL-style conflict cannot arise.
    assertThat(report.getStateVerdict().isStateEnumFound()).isFalse();
    // The 32-ish concrete *TypeItem collections must be surfaced (decision 8).
    assertThat(report.getCollectionSurvey().getConcreteWrapperFields()).isGreaterThan(20);
  }

  @Test
  void civilMatchesHigh() {
    RetrofitReport report = matchCivil();
    assumeTrue(report != null, "civil-service / civil-ccd-definition submodules not present; skipping");
    assertThat(report.isMapBased()).isFalse();
    assertThat(report.resolvedPercent()).isBetween(91.0, 97.0);
    // Civil's CaseState enum is directly reusable (18/18 IDs == constant names, no @JsonProperty).
    assertThat(report.getStateVerdict().isStateEnumFound()).isTrue();
    assertThat(report.getStateVerdict().getConflictingStates()).isZero();
    // Civil's Element<T> is generic and descends correctly — few concrete wrappers.
    assertThat(report.getCollectionSurvey().getGenericWrapperFields())
        .isGreaterThan(report.getCollectionSurvey().getConcreteWrapperFields());
  }

  @Test
  void sscsMatchesHighWithHostileWrappers() {
    RetrofitReport report = matchSscs();
    assumeTrue(report != null, "sscs-common / sscs-tribunals-case-api submodules not present; skipping");
    assertThat(report.isMapBased()).isFalse();
    assertThat(report.resolvedPercent()).isBetween(91.0, 97.0);
    // SSCS's State enum is reusable via an overridden @JsonValue toString() returning the id.
    assertThat(report.getStateVerdict().isStateEnumFound()).isTrue();
    assertThat(report.getStateVerdict().isToStringOverridden()).isTrue();
    assertThat(report.getStateVerdict().getConflictingStates()).isZero();
    // The most hostile collection wrapper of any archetype: concrete wrappers dominate.
    assertThat(report.getCollectionSurvey().getConcreteWrapperFields())
        .isGreaterThan(report.getCollectionSurvey().getGenericWrapperFields());
  }

  @Test
  void iaMapBasedModelIsNotApplicable() {
    // IA's AsylumCase extends HashMap<String,Object> — no fields to annotate (decision 6). It is not
    // a submodule of this repo; when the independently-cloned apps/ia checkout is present alongside
    // the workspace, validate against the real class, otherwise skip (the golden test covers the
    // map-based branch with a synthetic model regardless).
    Path iaModel = REPO_ROOT.resolve(
        "apps/ia/ia-case-api/src/main/java");
    Path definition = REPO_ROOT.resolve("test-projects/civil-ccd-definition/ccd-definition/civil");
    assumeTrue(Files.isDirectory(iaModel) && Files.isDirectory(definition),
        "apps/ia checkout or civil definition not present; skipping (golden test covers map-based)");
    RetrofitReport report = match(List.of(definition), "CIVIL", iaModel,
        "uk.gov.hmcts.reform.iacaseapi.domain.entities", "AsylumCase", Map.of());
    assertThat(report.isMapBased()).isTrue();
    assertThat(report.getNotApplicableReason()).contains("generate mode");
  }

  private RetrofitReport matchFpl() {
    Path definition = REPO_ROOT.resolve("test-builds/fpl-ccd-configuration/ccd-definition");
    Path model = REPO_ROOT.resolve("test-builds/fpl-ccd-configuration/service/src/main/java");
    if (!Files.isDirectory(definition) || !Files.isDirectory(model)) {
      return null;
    }
    Map<String, String> extra = Map.of(
        "shuttered", "CCD_DEF_SHUTTERED:true", "nonshuttered", "!CCD_DEF_SHUTTERED:true");
    return match(List.of(definition), "CARE_SUPERVISION_EPO", model,
        "uk.gov.hmcts.reform.fpl.model", "CaseData", extra);
  }

  private RetrofitReport matchEt() {
    Path definition = REPO_ROOT.resolve(
        "test-projects/et-ccd-callbacks/ccd-definitions/jurisdictions/england-wales/json");
    Path model = REPO_ROOT.resolve("test-projects/et-ccd-callbacks/et-shared/src/main/java");
    if (!Files.isDirectory(definition) || !Files.isDirectory(model)) {
      return null;
    }
    return match(List.of(definition), "ET_EnglandWales", model,
        "uk.gov.hmcts.et.common.model.ccd", "CaseData", Map.of());
  }

  private RetrofitReport matchCivil() {
    Path definition = REPO_ROOT.resolve("test-projects/civil-ccd-definition/ccd-definition/civil");
    Path model = REPO_ROOT.resolve("test-projects/civil-service/src/main/java");
    if (!Files.isDirectory(definition) || !Files.isDirectory(model)) {
      return null;
    }
    return match(List.of(definition), "CIVIL", model,
        "uk.gov.hmcts.reform.civil.model", "CaseData", Map.of());
  }

  private RetrofitReport matchSscs() {
    Path definition =
        REPO_ROOT.resolve("test-projects/sscs-tribunals-case-api/definitions/benefit/sheets");
    Path model = REPO_ROOT.resolve("test-projects/sscs-common/src/main/java");
    if (!Files.isDirectory(definition) || !Files.isDirectory(model)) {
      return null;
    }
    return match(List.of(definition), "Benefit", model,
        "uk.gov.hmcts.reform.sscs.ccd.domain", "SscsCaseData", Map.of());
  }

  private RetrofitReport match(List<Path> inputs, String caseType, Path modelRoot,
      String modelPackage, String modelClass, Map<String, String> extraSuffixes) {
    Map<String, OverlayCondition> overlays = new LinkedHashMap<>();
    overlays.put("prod", OverlayCondition.parse("CCD_DEF_ENV:prod"));
    overlays.put("nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"));
    extraSuffixes.forEach((name, spec) -> overlays.put(name, OverlayCondition.parse(spec)));
    ConversionOptions options = ConversionOptions.builder()
        .inputs(inputs)
        .caseTypeId(caseType)
        .modelPackage(modelPackage)
        .overlaySuffixes(overlays)
        .build();
    DefinitionIr ir = new JsonDefinitionReader().read(options, new GapCollector());
    return new RetrofitMatcher(ir, caseType, modelRoot, modelPackage, modelClass).match();
  }
}
