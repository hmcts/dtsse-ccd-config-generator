package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The eight real service-definition fixtures the round-trip suite gates against. Each lives in a
 * git submodule under {@code test-projects/}/{@code test-builds/}; the entries are shared by
 * {@link RoundTripTest} (which asserts each fixture's residuals against its checked-in baseline)
 * and {@link GenerateGoldenFiles} (which regenerates those baselines on demand), so the two never
 * drift apart.
 */
final class Fixtures {

  /**
   * Repository root, two levels up from the {@code sdk/ccd-definition-converter} module dir.
   */
  static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

  /**
   * One fixture: the baseline file name, the submodule input directories (relative to the repo
   * root), the case type to convert, the environment map, and any fixture-specific overlay
   * suffixes beyond prod/nonprod (shutter fragments).
   *
   * <p>{@code relativeInputs} is a list because a definition repo does not always build one case
   * type from one directory: finrem's build copies a shared {@code definitions/common/json} tree
   * into the per-case-type tree ({@code yarn copy-common-components-contested}) before invoking
   * {@code json2xlsx}, and the copies are gitignored — so the two roots must both be read to see
   * what the real xlsx contains. Every other fixture passes a single root. The list mirrors the
   * comma-separated definition-dir column in {@code bin/regen-review-clones.sh}'s {@code LANES}
   * table, so a fixture and its retrofit lane can describe the same input set.
   */
  record Fixture(
      String name,
      List<String> relativeInputs,
      String caseTypeId,
      Map<String, String> env,
      Map<String, String> extraSuffixes) {

    /** Convenience for the common single-root case. */
    Fixture(String name, String relativeInput, String caseTypeId,
            Map<String, String> env, Map<String, String> extraSuffixes) {
      this(name, List.of(relativeInput), caseTypeId, env, extraSuffixes);
    }

    List<Path> inputs() {
      return relativeInputs.stream().map(REPO_ROOT::resolve).toList();
    }

    /**
     * Whether every input root exists, i.e. the fixture's submodule is initialised. A fixture whose
     * submodule is absent skips rather than fails, so a fresh checkout without submodules builds.
     */
    boolean available() {
      return inputs().stream().allMatch(java.nio.file.Files::isDirectory);
    }
  }

  static final List<Fixture> ALL = List.of(
      new Fixture(
          "ia",
          "test-projects/ia-ccd-definitions/definitions/appeal/json",
          "Asylum",
          Map.of("CCD_DEF_ENV", "nonprod"),
          Map.of()),
      // sscs's build resolves the Publish column's ${CCD_DEF_PUBLISH} placeholder at xlsx-build
      // time: N unless Work Allocation is enabled (bin/create-xlsx.sh). This non-WA run mirrors that
      // with CCD_DEF_PUBLISH=N so the expected side substitutes the placeholder to the same literal
      // the converter's static N produces (a ${...} Publish leaves publishToCamunda unset → N).
      // sscs also ships AuthorisationCaseType shutter fragments (-shuttered/-nonshuttered, plus a
      // -WA-nonprod-nonshuttered variant): the default build (bin/create-xlsx.sh, SHUTTERED unset)
      // excludes *-shuttered.json. Model them as overlays keyed on CCD_DEF_SHUTTERED so a normal
      // nonprod run (the flag unset → false) includes exactly the -nonshuttered set on both sides,
      // mirroring the default build.
      //
      // The Work Allocation fragments are the other complementary pair, and CCD_DEF_PUBLISH is the
      // right variable to key them on rather than a flag of our own invention: create-xlsx.sh:128-136
      // derives BOTH from its WA_ENABLED argument in the same branch — WA on sets CCD_DEF_PUBLISH=Y
      // and excludes *-nonWA*, WA off sets N and excludes *-WA-*. Prod additionally forces N
      // (:140), so CCD_DEF_PUBLISH:Y implies nonprod and a single-variable predicate expresses the
      // *-WA-* exclusion exactly. Every -WA- spelling needs its own entry because extractOverlayTags
      // takes the LONGEST configured suffix: without GS-WA-nonprod, AuthorisationCaseField-GS-WA-
      // nonprod.json would match plain nonprod and lose its WA condition. WA-nonprod-nonshuttered is
      // a conjunction a single predicate cannot express; keying it on WA is right for every run here
      // (all non-shuttered) and the shutter half is the exclusion the shuttered build applies anyway.
      // test-preview is excluded by *preview.json in prod builds only, i.e. it is a nonprod overlay.
      new Fixture(
          "sscs",
          "test-projects/sscs-tribunals-case-api/definitions/benefit/sheets",
          "Benefit",
          Map.of("CCD_DEF_ENV", "nonprod", "CCD_DEF_PUBLISH", "N"),
          Map.of(
              "shuttered", "CCD_DEF_SHUTTERED:true",
              "nonshuttered", "!CCD_DEF_SHUTTERED:true",
              "nonWA", "!CCD_DEF_PUBLISH:Y",
              "WA-nonprod", "CCD_DEF_PUBLISH:Y",
              "GS-WA-nonprod", "CCD_DEF_PUBLISH:Y",
              "WA-fee-paid-nonprod", "CCD_DEF_PUBLISH:Y",
              "WA-nonprod-nonshuttered", "CCD_DEF_PUBLISH:Y",
              "test-preview", "!CCD_DEF_ENV:prod")),
      // fpl ships complementary shutter fragments (AuthorisationCaseType-shuttered/-nonshuttered):
      // the default (non-shuttered) build excludes *-shuttered.json and the shuttered build excludes
      // *-nonshuttered.json (bin/build-shuttered-ccd-definition.sh). Model them as overlays keyed on
      // CCD_DEF_SHUTTERED so a normal nonprod run (the flag unset → false) includes exactly the
      // -nonshuttered set on both sides, mirroring the default build.
      new Fixture(
          "fpl",
          "test-builds/fpl-ccd-configuration/ccd-definition",
          "CARE_SUPERVISION_EPO",
          Map.of("CCD_DEF_ENV", "nonprod"),
          Map.of(
              "shuttered", "CCD_DEF_SHUTTERED:true",
              "nonshuttered", "!CCD_DEF_SHUTTERED:true")),
      new Fixture(
          "et",
          "test-projects/et-ccd-callbacks/ccd-definitions/jurisdictions/england-wales/json",
          "ET_EnglandWales",
          Map.of("CCD_DEF_ENV", "nonprod"),
          Map.of()),
      // Civil ships AuthorisationCaseType shutter fragments (-shuttered/-unshuttered): the default
      // build (bin/build-release-ccd-definition.sh, activateShutter=false) excludes
      // *-shuttered.json. Model them as overlays keyed on CCD_DEF_SHUTTERED so a normal nonprod run
      // (the flag unset → false) includes exactly the -unshuttered set on both sides, mirroring the
      // default build.
      new Fixture(
          "civil",
          "test-projects/civil-ccd-definition/ccd-definition/civil",
          "CIVIL",
          Map.of("CCD_DEF_ENV", "nonprod"),
          Map.of(
              "shuttered", "CCD_DEF_SHUTTERED:true",
              "unshuttered", "!CCD_DEF_SHUTTERED:true")),
      new Fixture(
          "prl",
          "test-projects/prl-ccd-definitions/definitions/private-law/json",
          "PRLAPPS",
          Map.of("CCD_DEF_ENV", "nonprod"),
          Map.of()),
      // Probate leaves the Publish column as a ${CCD_DEF_PUBLISH} placeholder resolved at xlsx-build
      // time (as sscs does); CCD_DEF_PUBLISH=N mirrors a non-WA build so the expected side
      // substitutes it to the literal N the converter's static N produces. Probate ships shutter
      // fragments (AuthorisationCaseType-shutter/-unshutter, plus -wa- variants the -shutter/-unshutter
      // suffix also covers): the default build (shutterOption=false, createAllXLS.sh) excludes
      // *-shutter.json and the shuttered build excludes *-unshutter.json. Model them as overlays keyed
      // on CCD_DEF_SHUTTERED so a normal nonprod run (flag unset → false) includes exactly the
      // -unshutter set on both sides, mirroring the default build.
      new Fixture(
          "probate",
          "test-projects/probate-back-office/ccdImports/configFiles/CCD_Probate_Backoffice",
          "GrantOfRepresentation",
          Map.of("CCD_DEF_ENV", "nonprod", "CCD_DEF_PUBLISH", "N"),
          Map.of(
              "shutter", "CCD_DEF_SHUTTERED:true",
              "unshutter", "!CCD_DEF_SHUTTERED:true")),
      // finrem ships TWO case types from one definition repo — FinancialRemedyContested
      // (definitions/contested/json) and FinancialRemedyMVP2, the consented one
      // (definitions/consented/json) — plus a THIRD, shared tree, definitions/common/json, whose
      // fragments the build copies into whichever per-case-type tree it is about to build
      // (`yarn copy-common-components-contested`, package.json). Those copies are gitignored
      // (.gitignore: definitions/*/json/**/*-common*), so the common tree must be passed as a second
      // input root or the fixture reads a definition the real xlsx does not have. This entry covers
      // the contested type, the larger of the two (944 CaseField rows against consented's 309) and
      // the one whose tree carries every sheet the consented tree does (plus Categories, which the
      // consented tree lacks).
      //
      // Every common fragment addresses its rows to CaseTypeID=${CCD_DEF_CASE_TYPE_ID}, which
      // bin/contested/generate-excel-contested.sh sets to the case type it is building. CCD_DEF_
      // CASE_TYPE_ID is in this entry's env map for that reason, but it is NOT sufficient today:
      // the env map is only applied by ExpectedDefinitionBuilder (via Substitutor) on the expected
      // side of the comparison, while the converter's own read path never substitutes — the linker
      // reads raw IR rows, so DefinitionIr.rowsForCaseType compares the literal string
      // "${CCD_DEF_CASE_TYPE_ID}" against "FinancialRemedyContested" and discards every shared row
      // as another type's. Measured on this tree: a converter run over both roots emits no FR_close
      // event and none of the 12 shared CaseFields, and the two shared ComplexTypes (FR_caseMetrics,
      // FR_binFileUrls — which carry no CaseTypeID column and so survive the filter) are reported as
      // orphans precisely because the fields that would reference them were dropped. Pre-substituting
      // the placeholder on disk and re-running produces both FRCloseCommon and the shared CaseData
      // fields, which isolates the cause to the missing convert-side substitution. So this fixture
      // is expected to show a large residual until the converter substitutes ${CCD_DEF_*} on the
      // read path (or DefinitionIr resolves the placeholder before filtering); see the report
      // accompanying this wiring. finrem is the first fixture to exercise a placeholder CaseTypeID —
      // no other fixture's definition uses one.
      //
      // Overlay suffixes beyond prod/nonprod, all from the contested tree:
      //   - common: the shared fragments above. They are unconditional in a real build (copied in
      //     every time, no glob excludes them), so the predicate is one that is always true in this
      //     fixture's environment.
      //   - newPaperCase / manageScannedDocs: feature-flag fragments no generate-excel script
      //     excludes, i.e. likewise unconditionally included today.
      //   - express-v2-nonprod: nonprod-only, and it needs its own entry rather than matching plain
      //     `nonprod` because extractOverlayTags takes the LONGEST configured suffix — without it,
      //     AuthorisationCaseEvent-express-v2-nonprod.json would still read as nonprod, which is the
      //     same condition, but the suffix would then be unconfigured and resolveSheetAndSuffix
      //     rejects an unknown suffix outright.
      // The consented tree's extra `wa-nonprod` suffix is not configured here because this entry
      // does not read that tree; a consented fixture would add it keyed the way sscs keys WA.
      new Fixture(
          "finrem",
          List.of(
              "test-projects/finrem-ccd-definitions/definitions/contested/json",
              "test-projects/finrem-ccd-definitions/definitions/common/json"),
          "FinancialRemedyContested",
          Map.of("CCD_DEF_ENV", "nonprod", "CCD_DEF_CASE_TYPE_ID", "FinancialRemedyContested"),
          Map.of(
              "common", "!CCD_DEF_ENV:__never__",
              "newPaperCase", "!CCD_DEF_ENV:__never__",
              "manageScannedDocs", "!CCD_DEF_ENV:__never__",
              "express-v2-nonprod", "!CCD_DEF_ENV:prod")));

  private Fixtures() {
  }
}
