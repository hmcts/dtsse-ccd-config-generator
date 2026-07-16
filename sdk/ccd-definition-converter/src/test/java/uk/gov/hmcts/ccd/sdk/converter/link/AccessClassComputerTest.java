package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

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
}
