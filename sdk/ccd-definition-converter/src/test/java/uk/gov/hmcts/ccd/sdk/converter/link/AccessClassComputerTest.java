package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

/**
 * Direct unit tests for {@link AccessClassComputer}'s common-role-prefix elision, exercised
 * beneath the {@code DefaultDefinitionLinkerTest} end-to-end cases so the digest-fallback path
 * (a residual too wide for a semantic name, even after prefix stripping) is covered precisely.
 */
class AccessClassComputerTest {

  @Test
  void digestFallbackStillAppliesAfterPrefixStripping() {
    // 7 distinct roles on one field: covering them as 7 atoms exceeds MAX_CLASSES_PER_FIELD (6),
    // so the whole residual falls back to one dedicated class. All 7 share the "caseworker-
    // probate-" prefix (>=80%), so their tokens are stripped before nameFor tries the semantic
    // form; the remainders are still long enough (non-uniform CRUD) to overflow MAX_SEMANTIC_NAME,
    // so the digest-fallback form ("<role><crud>Plus<N>Roles<digest>Access") must still trigger.
    Map<String, String> residual = new LinkedHashMap<>();
    residual.put("caseworker-probate-administrationteam", "CRU");
    residual.put("caseworker-probate-systemupdateservice", "CU");
    residual.put("caseworker-probate-caseworkeradminuser", "R");
    residual.put("caseworker-probate-superuseraccessgroup", "CRUD");
    residual.put("caseworker-probate-exceptionsreportinguser", "R");
    residual.put("caseworker-probate-feepaymentprocessing", "CU");
    residual.put("caseworker-probate-staffadministrationrole", "R");

    AccessClassComputer computer = new AccessClassComputer(new GapCollector());
    AccessClassComputer.Result result = computer.compute(
        List.of("a"), Map.of("a", residual), Map.of());

    assertThat(result.accessClasses()).singleElement().satisfies(ac -> {
      String name = ac.getClassName();
      // Prefix-free: the shared "CaseworkerProbate" token must not appear anywhere in the name.
      assertThat(name).doesNotContain("CaseworkerProbate");
      // Still recognisable as the digest-fallback shape.
      assertThat(name).contains("Plus6Roles").endsWith("Access");
    });
  }

  @Test
  void singleRoleCaseTypeNeverStripsItsOwnFullNameAway() {
    // With only one distinct role, there is nothing to compare a "shared prefix" against, so the
    // role keeps its full token form rather than being treated as 100% its own prefix.
    AccessClassComputer computer = new AccessClassComputer(new GapCollector());
    AccessClassComputer.Result result = computer.compute(
        List.of("a"), Map.of("a", Map.of("caseworker-probate-solo", "CRU")), Map.of());

    assertThat(result.accessClasses()).singleElement()
        .extracting(AccessClassModel::getClassName)
        .isEqualTo("CaseworkerProbateSoloCruAccess");
  }

  @Test
  void injectedReadThatTheDefinitionDoesNotGrantIsRecordedAsAdvisoryOnly() {
    // The SDK injects a tab/search read (R) for a role the definition grants nothing on this
    // field. An access class cannot subtract it, so the divergence is only recorded — never a
    // passed-through row (residual() returns no PassthroughSheet). It must be a non-blocking
    // ADVISORY gap, forgiven on the round-trip by the TAB_READ_INJECTION comparator rule, not a
    // PASSTHROUGH_ROW that falsely implies a row was carried through.
    GapCollector gaps = new GapCollector();
    AccessClassComputer computer = new AccessClassComputer(gaps);
    // want: nothing for citizen on field "a"; have (injected): R for citizen on "a".
    computer.compute(
        List.of("a"), Map.of("a", Map.of()), Map.of("a", Map.of("citizen", "R")));

    assertThat(gaps.getEntries()).singleElement().satisfies(entry -> {
      assertThat(entry.getCategory()).isEqualTo(GapCategory.AUTH_NOT_DERIVABLE);
      assertThat(entry.getAction()).isEqualTo(GapAction.ADVISORY);
      assertThat(entry.getSheet()).isEqualTo("AuthorisationCaseField");
      assertThat(entry.getDetail()).contains("SDK injects R for role citizen")
          .contains("TAB_READ_INJECTION")
          .contains("no row is passed through");
    });
    // ADVISORY never blocks the conversion.
    assertThat(gaps.hasBlockingGaps()).isFalse();
  }

  @Test
  void aDefinitionGrantBeyondTheInjectedReadStillDerivesToAnAccessClassWithNoGap() {
    // Sanity guard on the residual maths: when the definition grants MORE than the injected R
    // (CRU vs injected R), the surplus is derivable to an access class and produces no gap at all —
    // the ADVISORY path fires strictly for the injected-only-R over-grant, nothing else.
    GapCollector gaps = new GapCollector();
    AccessClassComputer computer = new AccessClassComputer(gaps);
    AccessClassComputer.Result result = computer.compute(
        List.of("a"), Map.of("a", Map.of("citizen", "CRU")), Map.of("a", Map.of("citizen", "R")));

    assertThat(gaps.getEntries()).isEmpty();
    assertThat(result.accessClasses()).singleElement()
        .satisfies(ac -> assertThat(ac.getGrants()).containsEntry("citizen", "CU"));
  }
}
