package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Direct unit tests for {@link AccessClassComputer}'s naming and residual maths, exercised beneath
 * the {@code DefaultDefinitionLinkerTest} end-to-end cases so the digest-fallback path (a residual
 * too wide for a semantic name, even after prefix stripping) is covered precisely.
 */
class AccessClassComputerTest {

  @Test
  void digestFallbackStillAppliesAfterPrefixStripping() {
    // 9 distinct roles on one field: 9 atoms exceed MAX_CLASSES_PER_FIELD (5), so the field keeps 4
    // atoms and folds the other 5 into one combined class. All 9 share the "caseworker-probate-"
    // namespace, so those tokens are elided before naming; the folded remainders are still long
    // enough (non-uniform CRUD) to overflow MAX_SEMANTIC_NAME, so the combined class takes the
    // digest form ("<role><crud>Plus<N>Roles<digest>Access").
    Map<String, String> residual = new LinkedHashMap<>();
    residual.put("caseworker-probate-administrationteam", "CRU");
    residual.put("caseworker-probate-systemupdateservice", "CU");
    residual.put("caseworker-probate-caseworkeradminuser", "R");
    residual.put("caseworker-probate-superuseraccessgroup", "CRUD");
    residual.put("caseworker-probate-exceptionsreportinguser", "R");
    residual.put("caseworker-probate-feepaymentprocessing", "CU");
    residual.put("caseworker-probate-staffadministrationrole", "R");
    residual.put("caseworker-probate-bulkscanprocessor", "CRU");
    residual.put("caseworker-probate-registrarauthority", "RU");

    AccessClassComputer computer = new AccessClassComputer(new GapCollector());
    AccessClassComputer.Result result = computer.compute(
        List.of("a"), Map.of("a", residual), Map.of());

    assertThat(result.fieldClassNames().get("a")).hasSize(5);
    assertThat(result.accessClasses()).hasSize(5)
        .allSatisfy(ac -> assertThat(ac.getClassName()).doesNotContain("CaseworkerProbate"));
    assertThat(result.accessClasses()).filteredOn(ac -> ac.getGrants().size() > 1)
        .singleElement().satisfies(ac -> {
          assertThat(ac.getGrants()).hasSize(5);
          assertThat(ac.getClassName()).contains("Plus4Roles").endsWith("Access");
        });
    assertGrantsPreserved(result, Map.of("a", residual));
  }

  @Test
  void overCapFieldsShareACombinedClassBuiltFromTheClassesTheyAlreadyList() {
    // Two fields, seven atoms each, four in common; mixed CRUD so no uniform group is mined. Each
    // needs to lose two entries, so both fold three of their shared atoms into one combined class.
    Map<String, String> a = new LinkedHashMap<>();
    a.put("judge", "CRU");
    a.put("clerk", "R");
    a.put("admin", "CRUD");
    a.put("citizen", "RU");
    a.put("solicitor", "CR");
    a.put("barrister", "U");
    a.put("ombudsman", "D");
    Map<String, String> b = new LinkedHashMap<>();
    b.put("judge", "CRU");
    b.put("clerk", "R");
    b.put("admin", "CRUD");
    b.put("citizen", "RU");
    b.put("registrar", "CR");
    b.put("usher", "U");
    b.put("bailiff", "D");

    AccessClassComputer.Result result = new AccessClassComputer(new GapCollector())
        .compute(List.of("a", "b"), Map.of("a", a, "b", b), Map.of());

    List<String> namesA = result.fieldClassNames().get("a");
    List<String> namesB = result.fieldClassNames().get("b");
    assertThat(namesA).hasSize(5);
    assertThat(namesB).hasSize(5);
    List<String> sharedNames = namesA.stream().filter(namesB::contains).toList();
    assertThat(sharedNames).hasSize(2);
    AccessClassModel combined = classNamed(result, sharedNames.stream()
        .filter(n -> classNamed(result, n).getGrants().size() > 1).findFirst().orElseThrow());
    assertThat(combined.getGrants()).hasSize(3);
    assertThat(a.entrySet()).containsAll(combined.getGrants().entrySet());
    assertThat(b.entrySet()).containsAll(combined.getGrants().entrySet());
    // Named from its content, not the digest form.
    assertThat(combined.getClassName()).doesNotContain("Plus").endsWith("Access");
    assertGrantsPreserved(result, Map.of("a", a, "b", b));
  }

  @Test
  void sharedClassesWithNoReadableCombinedNameAreFoldedPerField() {
    // The two fields share three atoms, but their union names past MAX_SEMANTIC_NAME, so no
    // combined class is mined across them; each field folds its own tail instead.
    Map<String, String> shared = new LinkedHashMap<>();
    shared.put("supercalifragilisticexpialidocious-reviewer", "CRU");
    shared.put("antidisestablishmentarianism-approver", "R");
    shared.put("pneumonoultramicroscopic-auditor", "CRUD");
    Map<String, String> a = new LinkedHashMap<>(shared);
    a.put("judge", "RU");
    a.put("clerk", "CR");
    a.put("admin", "U");
    Map<String, String> b = new LinkedHashMap<>(shared);
    b.put("citizen", "RU");
    b.put("usher", "CR");
    b.put("bailiff", "U");

    AccessClassComputer.Result result = new AccessClassComputer(new GapCollector())
        .compute(List.of("a", "b"), Map.of("a", a, "b", b), Map.of());

    List<String> namesA = result.fieldClassNames().get("a");
    List<String> namesB = result.fieldClassNames().get("b");
    assertThat(namesA).hasSize(5);
    assertThat(namesB).hasSize(5);
    assertThat(namesA).noneMatch(
        n -> namesB.contains(n) && classNamed(result, n).getGrants().size() > 1);
    assertGrantsPreserved(result, Map.of("a", a, "b", b));
  }

  @Test
  void overCapFieldWithNothingToShareFoldsItsTailIntoOneClass() {
    // A uniform group (judge/clerk/admin=R on three fields) and five more atoms on field a: a keeps
    // its widest four classes, the group first, and folds the remaining two atoms into one class.
    Map<String, String> group = Map.of("judge", "R", "clerk", "R", "admin", "R");
    Map<String, String> a = new LinkedHashMap<>(group);
    a.put("citizen", "CRU");
    a.put("solicitor", "CR");
    a.put("barrister", "U");
    a.put("ombudsman", "D");
    a.put("registrar", "RU");

    AccessClassComputer.Result result = new AccessClassComputer(new GapCollector()).compute(
        List.of("a", "b", "c"), Map.of("a", a, "b", group, "c", group), Map.of());

    List<String> namesA = result.fieldClassNames().get("a");
    assertThat(namesA).hasSize(5);
    assertThat(classNamed(result, namesA.get(0)).getGrants()).isEqualTo(group);
    assertThat(classNamed(result, namesA.get(4)).getGrants()).hasSize(2);
    assertThat(result.fieldClassNames().get("b")).containsExactly(namesA.get(0));
    assertGrantsPreserved(result, Map.of("a", a, "b", group, "c", group));
  }

  @Test
  void everyFieldListsAtMostFiveClassesAndKeepsItsGrantsExactly() {
    String[] roles = {"judge", "clerk", "admin", "citizen", "solicitor", "barrister", "registrar",
        "usher", "bailiff", "ombudsman", "caseofficer", "homeoffice", "respondent", "interpreter"};
    String[] cruds = {"R", "CRU", "CRUD", "RU", "CR"};
    Random random = new Random(42);
    Map<String, Map<String, String>> desired = new LinkedHashMap<>();
    List<String> ids = new ArrayList<>();
    for (int f = 0; f < 60; f++) {
      Map<String, String> grants = new LinkedHashMap<>();
      for (String role : roles) {
        if (random.nextInt(3) > 0) {
          grants.put(role, cruds[random.nextInt(f % 2 == 0 ? 1 : cruds.length)]);
        }
      }
      desired.put("f" + f, grants);
      ids.add("f" + f);
    }

    AccessClassComputer.Result result =
        new AccessClassComputer(new GapCollector()).compute(ids, desired, Map.of());

    assertThat(result.fieldClassNames().values()).allSatisfy(names ->
        assertThat(names).hasSizeLessThanOrEqualTo(AccessClassComputer.MAX_CLASSES_PER_FIELD));
    assertThat(result.fieldClassNames().values()).anySatisfy(names -> assertThat(names).hasSize(5));
    assertGrantsPreserved(result, desired);
    // Every emitted class is referenced by some field.
    assertThat(result.accessClasses()).allSatisfy(ac ->
        assertThat(result.fieldClassNames().values())
            .anySatisfy(names -> assertThat(names).contains(ac.getClassName())));
  }

  private static AccessClassModel classNamed(AccessClassComputer.Result result, String name) {
    return result.accessClasses().stream().filter(ac -> ac.getClassName().equals(name))
        .findFirst().orElseThrow();
  }

  /** Each field's classes grant each role exactly its residual CRUD, and no other role. */
  private static void assertGrantsPreserved(
      AccessClassComputer.Result result, Map<String, Map<String, String>> expected) {
    expected.forEach((field, grants) -> {
      Map<String, Set<Character>> union = new TreeMap<>();
      for (String name : result.fieldClassNames().get(field)) {
        classNamed(result, name).getGrants().forEach((role, crud) ->
            union.computeIfAbsent(role, r -> new TreeSet<>()).addAll(CrudSet.parse(crud)));
      }
      Map<String, String> actual = new TreeMap<>();
      union.forEach((role, perms) -> actual.put(role, CrudSet.format(perms)));
      Map<String, String> want = new TreeMap<>();
      grants.forEach((role, crud) -> want.put(role, CrudSet.format(CrudSet.parse(crud))));
      assertThat(actual).as(field).isEqualTo(want);
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
  void definitionGrantBeyondTheInjectedReadStillDerivesToAnAccessClassWithNoGap() {
    // Sanity guard on the residual maths: when the definition grants MORE than the injected R
    // (CRU vs injected R), the role is derivable to an access class carrying its full definition
    // CRUD and produces no gap at all — the ADVISORY path fires strictly for the injected-only-R
    // over-grant, nothing else.
    GapCollector gaps = new GapCollector();
    AccessClassComputer computer = new AccessClassComputer(gaps);
    AccessClassComputer.Result result = computer.compute(
        List.of("a"), Map.of("a", Map.of("citizen", "CRU")), Map.of("a", Map.of("citizen", "R")));

    assertThat(gaps.getEntries()).isEmpty();
    assertThat(result.accessClasses()).singleElement()
        .satisfies(ac -> assertThat(ac.getGrants()).containsOnly(Map.entry("citizen", "CRU")));
  }

  @Test
  void oneLetterCaseRoleSeriesCollapsesInAGroupName() {
    // Three fields grant the same CRUD to [CHILDSOLICITORA]..[CHILDSOLICITORC]: one uniform group,
    // whose name collapses the contiguous one-letter series.
    Map<String, String> grants = Map.of(
        "[CHILDSOLICITORA]", "R", "[CHILDSOLICITORB]", "R", "[CHILDSOLICITORC]", "R");
    AccessClassComputer computer = new AccessClassComputer(new GapCollector());
    AccessClassComputer.Result result = computer.compute(
        List.of("a", "b", "c"), Map.of("a", grants, "b", grants, "c", grants), Map.of());

    assertThat(result.accessClasses()).singleElement().satisfies(ac -> {
      assertThat(ac.getClassName()).isEqualTo("ChildsolicitorAToCRAccess");
      assertThat(ac.getGrants()).isEqualTo(grants);
    });
    assertThat(result.fieldClassNames().values())
        .allSatisfy(names -> assertThat(names).containsExactly("ChildsolicitorAToCRAccess"));
  }
}
