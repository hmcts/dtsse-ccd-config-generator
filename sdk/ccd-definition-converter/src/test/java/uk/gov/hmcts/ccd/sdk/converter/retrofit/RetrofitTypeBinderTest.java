package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;

/**
 * Pins {@link RetrofitTypeBinder}'s reading of which definition type a row references, and the
 * same-simple-name preference it derives for {@link ModelSourceIndex#preferDeclaredClasses}.
 *
 * <p>Both exist because of measured lane regressions: probate's vestigial {@code FieldTypeParameter}
 * on a complex-typed row (which fabricated a second candidate class and so refused a unanimous
 * binding), and prl's two {@code OtherDocuments} classes (where an arbitrary tie-break annotated the
 * one nothing reaches and the definition's list emitted twice).
 */
class RetrofitTypeBinderTest {

  private static final String MODEL_PACKAGE = "m";

  private static DefinitionIr ir(List<Map<String, String>> caseFieldRows,
      List<Map<String, String>> complexTypeRows) {
    return ir(caseFieldRows, complexTypeRows, List.of());
  }

  private static DefinitionIr ir(List<Map<String, String>> caseFieldRows,
      List<Map<String, String>> complexTypeRows, List<Map<String, String>> fixedListRows) {
    ListMultimap<SheetName, SheetRow> rows = ArrayListMultimap.create();
    add(rows, SheetName.CASE_FIELD, caseFieldRows);
    add(rows, SheetName.COMPLEX_TYPES, complexTypeRows);
    add(rows, SheetName.FIXED_LISTS, fixedListRows);
    return new DefinitionIr(rows);
  }

  /**
   * A {@code FixedLists} row: one {@code ListElementCode} of a list, which is what
   * {@code codeCountsByListId} counts when deciding whether an enum can reproduce the list.
   */
  private static Map<String, String> listItem(String id, String listElementCode) {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("ID", id);
    columns.put("ListElementCode", listElementCode);
    return columns;
  }

  private static void add(ListMultimap<SheetName, SheetRow> rows, SheetName sheet,
      List<Map<String, String>> columnMaps) {
    for (Map<String, String> columns : columnMaps) {
      rows.put(sheet, SheetRow.builder()
          .sheet(sheet)
          .columns(new LinkedHashMap<>(columns))
          .overlayTags(java.util.Set.of())
          .build());
    }
  }

  /**
   * A {@code ComplexTypes} row, which is what makes an ID one the binder must resolve.
   */
  private static Map<String, String> member(String id, String listElementCode, String fieldType) {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("ID", id);
    columns.put("ListElementCode", listElementCode);
    columns.put("FieldType", fieldType);
    return columns;
  }

  private static Map<String, String> row(String id, String fieldType, String parameter) {
    Map<String, String> columns = new LinkedHashMap<>();
    columns.put("ID", id);
    columns.put("FieldType", fieldType);
    if (parameter != null) {
      columns.put("FieldTypeParameter", parameter);
    }
    return columns;
  }

  private static void write(Path root, String pkgPath, String simpleName, String body)
      throws Exception {
    Path dir = root.resolve(pkgPath);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(simpleName + ".java"), body);
  }

  private static Map<String, ResolvedProperty> rootProperties(ModelSourceIndex index, String fqn) {
    ModelSourceIndex.Type root = index.byFqn(fqn).orElseThrow();
    return new PropertyResolver(index).resolve(root).properties;
  }

  /**
   * probate's shape: four {@code Collection} rows whose fields are declared {@code Document}, plus one
   * row whose {@code FieldType} IS a complex type and which carries a leftover
   * {@code FieldTypeParameter} naming the same ID. Only the {@code Collection} rows reference
   * {@code ProbateDocument} as far as CCD is concerned, so the binding is unanimous.
   */
  @Test
  void ignoresAVestigialFieldTypeParameterOnAComplexTypedRow(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    write(src, "m", "Document",
        "package m;\nimport lombok.Data;\n@Data\npublic class Document { private String url; }\n");
    write(src, "m", "OriginalDocuments", "package m;\nimport lombok.Data;\n"
        + "@Data\npublic class OriginalDocuments { private String a; }\n");
    write(src, "m", "CaseData", "package m;\nimport java.util.List;\nimport lombok.Data;\n"
        + "import uk.gov.hmcts.ccd.sdk.type.ListValue;\n"
        + "@Data\npublic class CaseData {\n"
        + "  private List<ListValue<Document>> scannedDocuments;\n"
        + "  private OriginalDocuments originalDocuments;\n"
        + "}\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    Map<String, ResolvedProperty> properties = rootProperties(index, "m.CaseData");

    DefinitionIr definition = ir(
        List.of(
            row("scannedDocuments", "Collection", "ProbateDocument"),
            // The vestigial column: FieldType is itself a complex type, so CCD never reads the
            // parameter.
            row("originalDocuments", "OriginalDocuments", "ProbateDocument")),
        // ProbateDocument and OriginalDocuments are both complex types the definition declares.
        List.of(member("ProbateDocument", "url", "Text"),
            member("OriginalDocuments", "a", "Text")));

    // ProbateDocument is a definition-only ID (no model class of that name), so it is unbound and the
    // binder resolves it from its referencing fields. Read literally, the second row would make
    // OriginalDocuments a second candidate and the unanimity check would refuse the binding.
    Map<String, ModelSourceIndex.Type> bound =
        new RetrofitTypeBinder(index, MODEL_PACKAGE).bind(definition, "EXAMPLE", properties);

    assertThat(bound).containsKey("ProbateDocument");
    assertThat(bound.get("ProbateDocument").simpleName).isEqualTo("Document");
  }

  /**
   * The tie-break input: an ambiguous ID whose referencing field declares exactly one of the candidate
   * classes yields that class's FQN as the preference.
   */
  @Test
  void prefersTheClassTheDefinitionsOwnFieldDeclares(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    write(src, "m/complextypes", "OtherDocuments", "package m.complextypes;\nimport lombok.Data;\n"
        + "@Data\npublic class OtherDocuments { private String a; }\n");
    write(src, "m/dto/cafcass", "OtherDocuments", "package m.dto.cafcass;\nimport lombok.Data;\n"
        + "@Data\npublic class OtherDocuments { private String b; }\n");
    write(src, "m", "CaseData", "package m;\nimport java.util.List;\nimport lombok.Data;\n"
        + "import m.complextypes.OtherDocuments;\n"
        + "import uk.gov.hmcts.ccd.sdk.type.ListValue;\n"
        + "@Data\npublic class CaseData {\n"
        + "  private List<ListValue<OtherDocuments>> otherDocuments;\n"
        + "}\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    Map<String, ResolvedProperty> properties = rootProperties(index, "m.CaseData");

    DefinitionIr definition = ir(
        List.of(row("otherDocuments", "Collection", "OtherDocuments")),
        List.of(member("OtherDocuments", "a", "Text")));

    assertThat(index.isAmbiguousTopLevelClassName("OtherDocuments")).isTrue();
    assertThat(new RetrofitTypeBinder(index, MODEL_PACKAGE)
        .declaredClassPreferences(definition, "EXAMPLE", properties))
        .containsExactly("m.complextypes.OtherDocuments");
  }

  /**
   * sscs's shape, and the commonest one of all: a camelCase definition ID against the PascalCase class
   * it names. The ComplexTypes id {@code name} owns the class {@code Name}, so the id
   * {@code jointPartyName} — whose only referencing field, {@code JointParty.name}, is declared
   * {@code Name} — must NOT also bind to it. The refusal was case-SENSITIVE, so it missed this: `Name`
   * was pinned {@code @ComplexType(name = "jointPartyName")}, {@code CaseField[jointPartyName]} emitted
   * {@code FieldType=name}, the three {@code jointPartyName|*} rows had no counterpart at all, and
   * {@code name|title} inherited jointPartyName's FixedList typing where the definition has Text.
   */
  @Test
  void refusesToBindAnIdToAClassAnotherIdNamesCaseInsensitively(@TempDir Path work)
      throws Exception {
    Path src = work.resolve("src");
    write(src, "m", "Name", "package m;\nimport lombok.Data;\n"
        + "@Data\npublic class Name { private String title; }\n");
    write(src, "m", "JointParty", "package m;\nimport lombok.Data;\n"
        + "@Data\npublic class JointParty { private Name name; }\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n"
        + "@Data\npublic class CaseData { private JointParty jointParty; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    Map<String, ResolvedProperty> properties = rootProperties(index, "m.CaseData");

    DefinitionIr definition = ir(
        List.of(row("jointParty", "JointParty", null)),
        List.of(
            // Both are definition complex types. `name` resolves to the class Name case-insensitively,
            // exactly as ModelSourceIndex.complexTypeClass does, so that row owns it.
            member("JointParty", "name", "jointPartyName"),
            member("name", "title", "Text"),
            member("jointPartyName", "title", "FixedList(FL_titles)")));

    Map<String, ModelSourceIndex.Type> bound =
        new RetrofitTypeBinder(index, MODEL_PACKAGE).bind(definition, "EXAMPLE", properties);

    assertThat(bound).doesNotContainKey("jointPartyName");
  }

  /**
   * Unanimity-gated for the same reason a binding is: two fields referencing one ambiguous ID while
   * declaring different classes have no answer to give, so the existing tie-break is left to decide.
   */
  @Test
  void offersNoPreferenceWhenReferencingFieldsDisagree(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    write(src, "m/pkga", "DupType",
        "package m.pkga;\nimport lombok.Data;\n@Data\npublic class DupType { private String a; }\n");
    write(src, "m/pkgb", "DupType",
        "package m.pkgb;\nimport lombok.Data;\n@Data\npublic class DupType { private String b; }\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n"
        + "@Data\npublic class CaseData {\n"
        + "  private m.pkga.DupType one;\n"
        + "  private m.pkgb.DupType two;\n"
        + "}\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    Map<String, ResolvedProperty> properties = rootProperties(index, "m.CaseData");

    DefinitionIr definition = ir(
        List.of(row("one", "DupType", null), row("two", "DupType", null)),
        List.of(member("DupType", "a", "Text")));

    assertThat(new RetrofitTypeBinder(index, MODEL_PACKAGE)
        .declaredClassPreferences(definition, "EXAMPLE", properties))
        .isEmpty();
  }

  /**
   * Civil's two {@code Bundle}s, end to end: the preference must actually REACH the resolution when the
   * losing twin sits in a SUB-package of the model package.
   *
   * <p>prl's {@code OtherDocuments} above only proves the preference is computed and honoured for twins
   * that both sit OUTSIDE the model package, where the package hint separates neither and so declines to
   * answer. Civil is the harder shape and the one that stayed broken: {@code model.Bundle} carries the 13
   * fields {@code CaseData.caseBundles} reaches — the class the definition's {@code Bundle} describes —
   * while {@code model.bundle.Bundle} is a 1-field EM-stitching DTO nothing reaches, and
   * {@code model.bundle} <em>starts with</em> {@code model}. The hint therefore admitted BOTH and
   * returned the first one parsed, before the preference was consulted at all; the DTO won, took
   * {@code @ComplexType(name = "Bundle")} and 14 synthesised fields, and every label the patch wrote
   * landed on a class nothing reaches while the reachable twin emitted its members under its Java name.
   *
   * <p>Asserted through {@code complexTypeClass} with the preference installed the way
   * {@code RetrofitConverter} installs it, so it pins the whole path — the derivation AND the ordering
   * that lets it apply — rather than either half alone.
   */
  @Test
  void preferenceReachesATwinInASubPackageOfTheModelPackage(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    write(src, "m", "Bundle", "package m;\nimport lombok.Data;\nimport java.util.List;\n"
        + "@Data\npublic class Bundle { private String id; private String title; }\n");
    write(src, "m/bundle", "Bundle", "package m.bundle;\nimport lombok.Data;\n"
        + "@Data\npublic class Bundle { private BundleDetails value; }\n");
    write(src, "m/bundle", "BundleDetails", "package m.bundle;\nimport lombok.Data;\n"
        + "@Data\npublic class BundleDetails { private String id; }\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\nimport java.util.List;\n"
        + "@Data\npublic class CaseData { private List<Bundle> caseBundles; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    Map<String, ResolvedProperty> properties = rootProperties(index, "m.CaseData");

    // Civil's own shape: one root Collection row whose FieldTypeParameter is the ID, so the preference
    // is unanimous on the class CaseData.caseBundles declares.
    DefinitionIr definition = ir(
        List.of(row("caseBundles", "Collection", "Bundle")),
        List.of(member("Bundle", "id", "Text"), member("Bundle", "title", "Text")));

    RetrofitTypeBinder binder = new RetrofitTypeBinder(index, MODEL_PACKAGE);
    Set<String> preferences = binder.declaredClassPreferences(definition, "EXAMPLE", properties);
    assertThat(preferences).containsExactly("m.Bundle");

    index.preferDeclaredClasses(preferences);
    assertThat(index.complexTypeClass("Bundle", MODEL_PACKAGE))
        .get()
        .extracting(t -> t.fqn)
        .isEqualTo("m.Bundle");
  }

  /**
   * Civil's {@code ClaimTypeUnSpec}: a FixedList whose ID differs from its backing enum
   * ({@code ClaimTypeUnspec}) by ONE character's case must still bind.
   *
   * <p>Two case rules meet here and disagreed. The FixedLists gate in {@link RetrofitTypeBinder#bind}
   * admits an ID to the unbound set on a case-SENSITIVE test ({@code hasTopLevelType}), so an ID spelled
   * differently from its enum arrives here for binding — correctly, since nothing else will bind it. The
   * ownership refusal then matched the candidate enum's simple name against the definition's IDs
   * case-INSENSITIVELY and found... the same ID, back again. So the ID refused itself, and did so only
   * for the IDs whose sole defect is a case difference — exactly the ones needing the binding most.
   *
   * <p>The cost was the whole list twice over: the enum kept zero {@code @CCD} annotations, so
   * {@code FixedListGenerator} fell through to its constant-name fallback and emitted
   * {@code BREACH_OF_CONTRACT | BREACH_OF_CONTRACT} under the Java name {@code ClaimTypeUnspec}, where
   * the definition has {@code BREACH_OF_CONTRACT | Breach of contract} under {@code ClaimTypeUnSpec}.
   */
  @Test
  void bindsAFixedListWhoseIdDiffersFromItsEnumOnlyByCase(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    write(src, "m/enums", "ClaimTypeUnspec",
        "package m.enums;\npublic enum ClaimTypeUnspec { PERSONAL_INJURY, BREACH_OF_CONTRACT }\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\n"
        + "import m.enums.ClaimTypeUnspec;\n"
        + "@Data\npublic class CaseData { private ClaimTypeUnspec claimTypeUnSpec; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    Map<String, ResolvedProperty> properties = rootProperties(index, "m.CaseData");

    // The definition spells the ID with a capital S; the enum does not. Nothing else in the definition
    // claims the enum, so there is no rival row and the binding is the only way the list is emitted.
    DefinitionIr definition = ir(
        List.of(row("claimTypeUnSpec", "FixedRadioList", "ClaimTypeUnSpec")),
        List.of(),
        List.of(listItem("ClaimTypeUnSpec", "PERSONAL_INJURY"),
            listItem("ClaimTypeUnSpec", "BREACH_OF_CONTRACT")));

    Map<String, ModelSourceIndex.Type> bound =
        new RetrofitTypeBinder(index, MODEL_PACKAGE).bind(definition, "EXAMPLE", properties);

    assertThat(bound).containsKey("ClaimTypeUnSpec");
    assertThat(bound.get("ClaimTypeUnSpec").fqn).isEqualTo("m.enums.ClaimTypeUnspec");
  }

  /**
   * The refusal still fires for a genuine rival, which is what keeps the sscs {@code jointPartyName}
   * regression fixed while {@code ClaimTypeUnSpec} above binds: excluding an ID from its OWN ownership
   * test must not stop a DIFFERENT ID's claim being honoured.
   *
   * <p>Here the definition declares both a list {@code Status} — which names the enum outright — and a
   * list {@code caseStatus} whose referencing field is declared as that same enum. {@code Status} owns
   * it, so {@code caseStatus} must not also be pinned onto it.
   */
  @Test
  void stillRefusesAFixedListWhoseEnumAnotherListIdNames(@TempDir Path work) throws Exception {
    Path src = work.resolve("src");
    write(src, "m/enums", "Status", "package m.enums;\npublic enum Status { OPEN, CLOSED }\n");
    write(src, "m", "CaseData", "package m;\nimport lombok.Data;\nimport m.enums.Status;\n"
        + "@Data\npublic class CaseData { private Status caseStatus; }\n");
    ModelSourceIndex index = ModelSourceIndex.parse(src);
    Map<String, ResolvedProperty> properties = rootProperties(index, "m.CaseData");

    DefinitionIr definition = ir(
        List.of(row("caseStatus", "FixedList", "caseStatus")),
        List.of(),
        List.of(listItem("Status", "OPEN"), listItem("Status", "CLOSED"),
            listItem("caseStatus", "OPEN"), listItem("caseStatus", "CLOSED")));

    Map<String, ModelSourceIndex.Type> bound =
        new RetrofitTypeBinder(index, MODEL_PACKAGE).bind(definition, "EXAMPLE", properties);

    assertThat(bound).doesNotContainKey("caseStatus");
  }
}
