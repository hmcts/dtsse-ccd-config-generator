package uk.gov.hmcts.ccd.sdk.converter.roundtrip;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The seven real service-definition fixtures the round-trip suite gates against. Each lives in a
 * git submodule under {@code test-projects/}/{@code test-builds/}; the entries are shared by
 * {@link RoundTripTest} (which asserts each fixture's residuals against its checked-in baseline)
 * and {@link GenerateGoldenFiles} (which regenerates those baselines on demand), so the two never
 * drift apart.
 */
final class Fixtures {

  /** Repository root, two levels up from the {@code sdk/ccd-definition-converter} module dir. */
  static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();

  /**
   * One fixture: the baseline file name, the submodule input directory (relative to the repo
   * root), the case type to convert, the environment map, and any fixture-specific overlay
   * suffixes beyond prod/nonprod (shutter fragments).
   */
  record Fixture(
      String name,
      String relativeInput,
      String caseTypeId,
      Map<String, String> env,
      Map<String, String> extraSuffixes) {

    Path input() {
      return REPO_ROOT.resolve(relativeInput);
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
      // -WA-nonprod-nonshuttered variant the -nonshuttered suffix also covers): the default build
      // (bin/create-xlsx.sh, SHUTTERED unset) excludes *-shuttered.json. Model them as overlays keyed
      // on CCD_DEF_SHUTTERED so a normal nonprod run (the flag unset → false) includes exactly the
      // -nonshuttered set on both sides, mirroring the default build.
      new Fixture(
          "sscs",
          "test-projects/sscs-tribunals-case-api/definitions/benefit/sheets",
          "Benefit",
          Map.of("CCD_DEF_ENV", "nonprod", "CCD_DEF_PUBLISH", "N"),
          Map.of(
              "shuttered", "CCD_DEF_SHUTTERED:true",
              "nonshuttered", "!CCD_DEF_SHUTTERED:true")),
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
              "unshutter", "!CCD_DEF_SHUTTERED:true")));

  private Fixtures() {
  }
}
