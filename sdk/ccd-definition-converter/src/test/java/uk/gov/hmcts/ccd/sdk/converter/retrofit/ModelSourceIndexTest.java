package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link ModelSourceIndex#topLevelFqnsOutside} — the cross-package type-FQN map the retrofit
 * companion emitter feeds {@code JavaTypeParser} so a generated complex type in the model package
 * imports member types that really live in sibling sub-packages (finding C2, pilot-fixed: ET's
 * {@code OrganisationUsersIdamUser} in {@code model.ccd.types}). Verifies a unique sibling type IS
 * mapped and an ambiguous simple name is NOT (the D1 blocker: it is dropped rather than guessed).
 */
class ModelSourceIndexTest {

  private static final Path MODEL_ROOT =
      Path.of("src/test/resources/retrofit/model/src").toAbsolutePath();

  @Test
  void mapsUniqueSiblingPackageTypesForCompanionImports() {
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    // From the root model package's perspective, common.Party / common.DocItem / event.HearingEventData
    // live in sibling sub-packages and each has a unique simple name — so a companion emitted into the
    // root package can import them at their real FQN (this is exactly the C2 mechanism).
    Map<String, String> fqns = index.topLevelFqnsOutside("uk.gov.hmcts.example.model");
    assertThat(fqns).containsEntry("Party", "uk.gov.hmcts.example.model.common.Party");
    assertThat(fqns).containsEntry("DocItem", "uk.gov.hmcts.example.model.common.DocItem");
    assertThat(fqns).containsEntry("ClaimType", "uk.gov.hmcts.example.enums.ClaimType");
  }

  @Test
  void dropsAnAmbiguousSimpleNameWithoutAHint() {
    // DupType is declared in both model.pkga and model.pkgb — without a hint the resolver refuses to
    // guess and drops it from the FQN map (finding D1), so a companion referencing it would not
    // resolve rather than binding to an arbitrary one.
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    assertThat(index.topLevelFqnsOutside("uk.gov.hmcts.example.model")).doesNotContainKey("DupType");
  }

  @Test
  void resolvesAnAmbiguousSimpleNameWithAPackageHint() {
    // A --type-package-hint pinning DupType to model.pkgb resolves the ambiguity (finding D1).
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    Map<String, String> hints = Map.of("DupType", "uk.gov.hmcts.example.model.pkgb");
    Map<String, String> fqns = index.topLevelFqnsOutside("uk.gov.hmcts.example.model", hints);
    assertThat(fqns).containsEntry("DupType", "uk.gov.hmcts.example.model.pkgb.DupType");
  }

  @Test
  void reportsWhetherATypeExistsInAHintedPackage() {
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    assertThat(index.hasTopLevelTypeInPackage("DupType", "uk.gov.hmcts.example.model.pkga")).isTrue();
    assertThat(index.hasTopLevelTypeInPackage("DupType", "uk.gov.hmcts.example.model.nope")).isFalse();
    assertThat(index.hasTopLevelTypeInPackage("NoSuchType", "uk.gov.hmcts.example.model")).isFalse();
  }

  @Test
  void resolvesComplexTypeClassExactlyBySimpleName() {
    // The definition's ComplexTypes ID "Party" is already PascalCase and matches the class exactly.
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    assertThat(index.complexTypeClass("Party", "uk.gov.hmcts.example.model"))
        .isPresent()
        .get()
        .extracting(t -> t.simpleName)
        .isEqualTo("Party");
  }

  @Test
  void resolvesCamelCaseComplexTypeIdToItsPascalCaseClass() {
    // Finding A2: SSCS's ComplexTypes ID is camelCase ("reasonableAdjustmentsLetters") while its
    // model class is PascalCase (ReasonableAdjustmentsLetters). The SDK's ComplexTypeEmitter maps the
    // two by first-letter capitalisation, so a case-sensitive lookup on the camelCase ID misses the
    // class and the complex type is silently emitted as a spurious companion, dropping its members'
    // @CCD/typeParameterOverride. complexTypeClass must therefore resolve the camelCase ID to the
    // PascalCase class case-insensitively. Here the golden model's "Party" class is reached via the
    // camelCase id "party" exactly as SSCS reaches ReasonableAdjustmentsLetters via
    // "reasonableAdjustmentsLetters".
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    assertThat(index.complexTypeClass("party", "uk.gov.hmcts.example.model"))
        .as("camelCase complex-type id must resolve to its PascalCase class")
        .isPresent()
        .get()
        .extracting(t -> t.simpleName)
        .isEqualTo("Party");
  }

  @Test
  void returnsEmptyForAComplexTypeIdWithNoModelClass() {
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    assertThat(index.complexTypeClass("noSuchComplexType", "uk.gov.hmcts.example.model")).isEmpty();
  }

  @Test
  void aliasesADerivedNameToTheAcronymCasedClassTheComplexTypeBindsTo(@TempDir Path work)
      throws Exception {
    // ET: the ComplexTypes ID et3CaseDetailsLinksStatuses PascalCases (TypeClassNamer) to
    // Et3CaseDetailsLinksStatuses, while the class it binds to is the acronym-cased
    // ET3CaseDetailsLinksStatuses. No companion is emitted under the derived name (the type binds), so
    // without this alias every reference to it resolved to a modelPackage.Et3CaseDetailsLinksStatuses
    // that exists nowhere — cannot find symbol.
    Path src = work.resolve("src");
    write(src, "m/types", "ET3CaseDetailsLinksStatuses",
        "package m.types;\npublic class ET3CaseDetailsLinksStatuses { private String a; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);

    assertThat(index.complexTypeIdClassAliases(List.of("et3CaseDetailsLinksStatuses"), "m"))
        .containsEntry("Et3CaseDetailsLinksStatuses", "m.types.ET3CaseDetailsLinksStatuses");
  }

  @Test
  void aliasesNothingWhenTheDerivedNameAlreadyMatchesTheBoundClass(@TempDir Path work)
      throws Exception {
    // A camelCase ID whose class differs only in the leading character is already handled by the
    // derived name itself, so no alias is wanted (aliasing it would be a no-op entry at best).
    Path src = work.resolve("src");
    write(src, "m/types", "Party", "package m.types;\npublic class Party { private String a; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);

    assertThat(index.complexTypeIdClassAliases(List.of("party"), "m")).isEmpty();
  }

  @Test
  void aliasesNothingForADefinitionOnlyComplexType(@TempDir Path work) throws Exception {
    // No model class → a companion IS generated under the derived name, so an alias would redirect a
    // reference away from the class that is about to be emitted.
    Path src = work.resolve("src");
    write(src, "m/types", "Party", "package m.types;\npublic class Party { private String a; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);

    assertThat(index.complexTypeIdClassAliases(List.of("noSuchComplexType"), "m")).isEmpty();
  }

  @Test
  void neverShadowsATypeTheModelDeclaresUnderTheDerivedName(@TempDir Path work) throws Exception {
    // The model declares BOTH the acronym-cased class the ID binds to AND an unrelated type under the
    // derived name. A reference to the derived name is already correct; rebinding it would silently
    // point it at the other class.
    Path src = work.resolve("src");
    write(src, "m/types", "ET3Links",
        "package m.types;\npublic class ET3Links { private String a; }\n");
    write(src, "m/other", "Et3Links",
        "package m.other;\npublic class Et3Links { private String b; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);

    assertThat(index.complexTypeIdClassAliases(List.of("et3Links"), "m"))
        .doesNotContainKey("Et3Links");
  }

  // ---- suppressed-getter repair (RetrofitUnsuppressedGetters) ----

  /**
   * sscs's {@code SscsCaseData} shape: a {@code @JsonUnwrapped} member whose Lombok getter is
   * suppressed, with a differently-named hand-written accessor that {@code PropertyUtils} would map to
   * a non-existent field. The class-level {@code @Data} is what would otherwise generate the getter.
   */
  private static final String SUPPRESSED_UNWRAPPED_CASE_DATA = "package m;\n"
      + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n"
      + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
      + "@Data\npublic class CaseData {\n"
      + "  @JsonUnwrapped\n"
      + "  @Getter(AccessLevel.NONE)\n"
      + "  private FinalDecision finalDecisionCaseData;\n"
      + "  public FinalDecision getSscsFinalDecisionCaseData() { return finalDecisionCaseData; }\n"
      + "}\n";

  @Test
  void refusesASuppressedGetterUntilTheRepairIsEnabled(@TempDir Path work) throws Exception {
    // The historical answer, which every non-retrofit caller (the matcher's report-only pass, generate
    // mode) must keep seeing: the getter does not exist, so the placement refuses and the row falls back
    // to verbatim passthrough rather than emitting an invalid method reference.
    Path src = work.resolve("src");
    write(src, "m", "FinalDecision",
        "package m;\nimport lombok.Data;\n@Data\npublic class FinalDecision { private String a; }\n");
    write(src, "m", "CaseData", SUPPRESSED_UNWRAPPED_CASE_DATA);
    ModelSourceIndex index = ModelSourceIndex.parse(src);

    assertThat(index.hasResolvableGetter(index.byFqn("m.CaseData").orElseThrow(),
        "finalDecisionCaseData")).isFalse();
    assertThat(index.unsuppressedGetters().isEmpty())
        .as("nothing is planned when the repair is off")
        .isTrue();
  }

  @Test
  void resolvesASuppressedUnwrappedGetterAndRecordsTheRepair(@TempDir Path work) throws Exception {
    // With the repair on, the getter resolves BECAUSE the patch will delete the suppression — and the
    // reliance is recorded at that moment, so the patch removes exactly what was relied on.
    Path src = work.resolve("src");
    write(src, "m", "FinalDecision",
        "package m;\nimport lombok.Data;\n@Data\npublic class FinalDecision { private String a; }\n");
    write(src, "m", "CaseData", SUPPRESSED_UNWRAPPED_CASE_DATA);
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    index.repairSuppressedGetters(RetrofitUnsuppressedGetters.empty());

    assertThat(index.hasResolvableGetter(index.byFqn("m.CaseData").orElseThrow(),
        "finalDecisionCaseData")).isTrue();
    assertThat(index.unsuppressedGetters().all()).singleElement().satisfies(u -> {
      assertThat(u.ownerFqn()).isEqualTo("m.CaseData");
      assertThat(u.memberName()).isEqualTo("finalDecisionCaseData");
      assertThat(u.file().getFileName().toString()).isEqualTo("CaseData.java");
    });
  }

  @Test
  void neverUnsuppressesAFieldJacksonCannotAlreadySee(@TempDir Path work) throws Exception {
    // No @JsonUnwrapped: the field is private with no getter, so it is invisible to Jackson today and
    // un-suppressing it would start serialising a brand-new property. The repair must refuse, leaving
    // the placement to fall back as before.
    Path src = work.resolve("src");
    write(src, "m", "CaseData", "package m;\n"
        + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
        + "@Data\npublic class CaseData {\n"
        + "  @Getter(AccessLevel.NONE)\n"
        + "  private String secret;\n}\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    index.repairSuppressedGetters(RetrofitUnsuppressedGetters.empty());

    assertThat(index.hasResolvableGetter(index.byFqn("m.CaseData").orElseThrow(), "secret"))
        .isFalse();
    assertThat(index.unsuppressedGetters().isEmpty()).isTrue();
  }

  @Test
  void refusesToRepairASuppressionSharingItsLineWithTheDeclaration(@TempDir Path work)
      throws Exception {
    // The patch deletes a WHOLE line, so it can only remove a suppression that sits alone on one. This
    // predicate is checked here, at record time, precisely so a placement can never resolve through a
    // repair the emitter would then decline to make.
    Path src = work.resolve("src");
    write(src, "m", "FinalDecision",
        "package m;\nimport lombok.Data;\n@Data\npublic class FinalDecision { private String a; }\n");
    write(src, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n"
        + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
        + "@Data\npublic class CaseData {\n"
        + "  @JsonUnwrapped @Getter(AccessLevel.NONE) private FinalDecision finalDecisionCaseData;\n"
        + "}\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    index.repairSuppressedGetters(RetrofitUnsuppressedGetters.empty());

    assertThat(index.hasResolvableGetter(index.byFqn("m.CaseData").orElseThrow(),
        "finalDecisionCaseData")).isFalse();
    assertThat(index.unsuppressedGetters().isEmpty()).isTrue();
  }

  @Test
  void leavesAHandWrittenGetterOfTheStandardNameAlone(@TempDir Path work) throws Exception {
    // The getter already exists, so the suppression is irrelevant and nothing must be edited: an
    // un-suppression here would make Lombok generate a duplicate method.
    Path src = work.resolve("src");
    write(src, "m", "FinalDecision",
        "package m;\nimport lombok.Data;\n@Data\npublic class FinalDecision { private String a; }\n");
    write(src, "m", "CaseData", "package m;\n"
        + "import com.fasterxml.jackson.annotation.JsonUnwrapped;\n"
        + "import lombok.AccessLevel;\nimport lombok.Data;\nimport lombok.Getter;\n"
        + "@Data\npublic class CaseData {\n"
        + "  @JsonUnwrapped\n"
        + "  @Getter(AccessLevel.NONE)\n"
        + "  private FinalDecision finalDecisionCaseData;\n"
        + "  public FinalDecision getFinalDecisionCaseData() { return finalDecisionCaseData; }\n"
        + "}\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    index.repairSuppressedGetters(RetrofitUnsuppressedGetters.empty());

    assertThat(index.hasResolvableGetter(index.byFqn("m.CaseData").orElseThrow(),
        "finalDecisionCaseData")).isTrue();
    assertThat(index.unsuppressedGetters().isEmpty())
        .as("an existing getter needs no repair")
        .isTrue();
  }

  // ---- same-simple-name tie-break (preferDeclaredClasses) ----

  @Test
  void reportsWhenATopLevelClassNameIsAmbiguous() {
    // DupType is declared in model.pkga AND model.pkgb; Party once. The binder asks this before
    // spending a declaration on a preference.
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    assertThat(index.isAmbiguousTopLevelClassName("DupType")).isTrue();
    assertThat(index.isAmbiguousTopLevelClassName("Party")).isFalse();
    assertThat(index.isAmbiguousTopLevelClassName("NoSuchType")).isFalse();
  }

  @Test
  void resolvesATiedSimpleNameToThePreferredDeclaredClass(@TempDir Path work) throws Exception {
    // prl's OtherDocuments: two top-level classes, NEITHER under the model package (which is
    // models.dto.ccd, a sibling of both), so the package-hint branch does not fire and the resolution
    // fell through to an arbitrary first-parsed pick. When the pick is the twin the definition's own
    // field does NOT declare, the patch annotates a class nothing reaches and the reached one keeps its
    // bare enum member — the generator then emits BOTH lists (69 correct rows + 72 spurious).
    Path src = work.resolve("src");
    write(src, "m/complextypes", "OtherDocuments",
        "package m.complextypes;\npublic class OtherDocuments { private String a; }\n");
    write(src, "m/dto/cafcass", "OtherDocuments",
        "package m.dto.cafcass;\npublic class OtherDocuments { private String b; }\n");

    ModelSourceIndex index = ModelSourceIndex.parse(src);
    index.preferDeclaredClasses(List.of("m.complextypes.OtherDocuments"));
    assertThat(index.complexTypeClass("OtherDocuments", "m.dto.ccd"))
        .isPresent()
        .get()
        .extracting(t -> t.fqn)
        .isEqualTo("m.complextypes.OtherDocuments");

    // And the other way round, to prove the preference is what decides it rather than parse order.
    ModelSourceIndex other = ModelSourceIndex.parse(src);
    other.preferDeclaredClasses(List.of("m.dto.cafcass.OtherDocuments"));
    assertThat(other.complexTypeClass("OtherDocuments", "m.dto.ccd"))
        .get()
        .extracting(t -> t.fqn)
        .isEqualTo("m.dto.cafcass.OtherDocuments");
  }

  @Test
  void packageHintStillOutranksAPreference(@TempDir Path work) throws Exception {
    // The preference is the LAST resort, consulted only where the existing rules leave the tie open. A
    // candidate under the requested package still wins, so no lane that resolves today can move.
    Path src = work.resolve("src");
    write(src, "m/model/pkga", "DupType",
        "package m.model.pkga;\npublic class DupType { private String a; }\n");
    write(src, "m/other", "DupType", "package m.other;\npublic class DupType { private String b; }\n");

    ModelSourceIndex index = ModelSourceIndex.parse(src);
    index.preferDeclaredClasses(List.of("m.other.DupType"));

    assertThat(index.complexTypeClass("DupType", "m.model"))
        .get()
        .extracting(t -> t.fqn)
        .isEqualTo("m.model.pkga.DupType");
  }

  @Test
  void preferenceNeverRemovesAResolutionItDoesNotCover() {
    // The tie-break is additive: an ID whose name is not ambiguous, and an ambiguous one with no
    // preference installed, both resolve exactly as before. A refusal here instead of a redirect broke
    // the prl lane's build — ambiguous IDs routed to companions whose own member types are also
    // ambiguous (ServingRespondentsEnum, ServeOrderDetails) and so unresolvable.
    ModelSourceIndex index = ModelSourceIndex.parse(MODEL_ROOT);
    index.preferDeclaredClasses(List.of("uk.gov.hmcts.example.model.pkgb.DupType"));

    assertThat(index.complexTypeClass("Party", "uk.gov.hmcts.example.model")).isPresent();

    ModelSourceIndex unhinted = ModelSourceIndex.parse(MODEL_ROOT);
    assertThat(index.complexTypeClass("DupType", "uk.gov.hmcts.example.model")).isPresent();
    assertThat(unhinted.complexTypeClass("DupType", "uk.gov.hmcts.example.model")).isPresent();
  }

  private static void write(Path root, String pkgPath, String simpleName, String body)
      throws Exception {
    Path dir = root.resolve(pkgPath);
    java.nio.file.Files.createDirectories(dir);
    java.nio.file.Files.writeString(dir.resolve(simpleName + ".java"), body);
  }
}
