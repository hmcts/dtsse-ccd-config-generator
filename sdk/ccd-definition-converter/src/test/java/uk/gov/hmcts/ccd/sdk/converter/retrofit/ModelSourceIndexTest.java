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

  private static void write(Path root, String pkgPath, String simpleName, String body)
      throws Exception {
    Path dir = root.resolve(pkgPath);
    java.nio.file.Files.createDirectories(dir);
    java.nio.file.Files.writeString(dir.resolve(simpleName + ".java"), body);
  }
}
