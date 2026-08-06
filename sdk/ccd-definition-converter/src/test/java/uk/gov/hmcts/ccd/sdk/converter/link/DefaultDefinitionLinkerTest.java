package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.converter.link.IrBuilder.cols;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.model.AccessClassModel;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.EventModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FixedListModel;
import uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition;
import uk.gov.hmcts.ccd.sdk.converter.model.PageModel;
import uk.gov.hmcts.ccd.sdk.converter.model.PassthroughSheet;
import uk.gov.hmcts.ccd.sdk.converter.model.RetrofitModelTypeGraph;
import uk.gov.hmcts.ccd.sdk.converter.model.RoleModel;
import uk.gov.hmcts.ccd.sdk.converter.model.StateModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCategory;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapEntry;

class DefaultDefinitionLinkerTest {

  private final DefaultDefinitionLinker linker = new DefaultDefinitionLinker();

  private ConversionOptions options(String caseTypeId) {
    return ConversionOptions.builder()
        .caseTypeId(caseTypeId)
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .build();
  }

  private ConversionOptions optionsWithOverlay() {
    return ConversionOptions.builder()
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .overlaySuffixes(Map.of("prod", OverlayCondition.parse("CCD_DEF_ENV:prod")))
        .build();
  }

  private IrBuilder minimal(String caseTypeId) {
    return IrBuilder.builder()
        .row(SheetName.JURISDICTION, cols("ID", "TEST", "Name", "Test Jurisdiction"))
        .row(SheetName.CASE_TYPE,
            cols("ID", caseTypeId, "Name", "Case", "JurisdictionID", "TEST"));
  }

  @Test
  void selectsExplicitCaseType() {
    DefinitionIr ir = minimal("Minimal").build();
    CaseTypeModel model = linker.link(ir, options("Minimal"), new GapCollector());
    assertThat(model.getCaseTypeId()).isEqualTo("Minimal");
    assertThat(model.getJurisdictionId()).isEqualTo("TEST");
  }

  @Test
  void selectsSingleCaseTypeImplicitly() {
    DefinitionIr ir = minimal("OnlyOne").build();
    ConversionOptions options = ConversionOptions.builder()
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .build();
    CaseTypeModel model = linker.link(ir, options, new GapCollector());
    assertThat(model.getCaseTypeId()).isEqualTo("OnlyOne");
  }

  @Test
  void failsWhenCaseTypeAmbiguous() {
    DefinitionIr ir = IrBuilder.builder()
        .row(SheetName.CASE_TYPE, cols("ID", "A", "Name", "A"))
        .row(SheetName.CASE_TYPE, cols("ID", "B", "Name", "B"))
        .build();
    ConversionOptions options = ConversionOptions.builder()
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .build();
    assertThatThrownBy(() -> linker.link(ir, options, new GapCollector()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Multiple case types");
  }

  @Test
  void buildsStatesAndSanitisesIllegalStateAsBlockingGap() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.STATE, cols("CaseTypeID", "Minimal", "ID", "Open", "Name", "Open"))
        .row(SheetName.STATE,
            cols("CaseTypeID", "Minimal", "ID", "Closed", "Name", "Closed",
                "TitleDisplay", "# closed"))
        .row(SheetName.STATE, cols("CaseTypeID", "Minimal", "ID", "1Invalid", "Name", "Bad"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getStates()).extracting(StateModel::getId).containsExactly("Open", "Closed");
    assertThat(model.getStates()).filteredOn(s -> s.getId().equals("Closed"))
        .singleElement()
        .extracting(StateModel::getTitleDisplay).isEqualTo("# closed");
    assertThat(gaps.getEntries())
        .anyMatch(g -> g.getCategory() == GapCategory.IDENTIFIER_SANITISED
            && "1Invalid".equals(g.getValue()));
    assertThat(gaps.hasBlockingGaps()).isTrue();
  }

  @Test
  void buildsRolesAcrossAuthorisationSheetsAndCaseRoles() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.AUTHORISATION_CASE_TYPE,
            cols("CaseTypeID", "Minimal", "UserRole", "caseworker-test", "CRUD", "CRUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "x", "UserRole", "citizen", "CRUD", "R"))
        .row(SheetName.CASE_ROLE,
            cols("CaseTypeID", "Minimal", "ID", "[CREATOR]", "Name", "Creator"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getRoles()).extracting(RoleModel::getId)
        .contains("caseworker-test", "citizen", "[CREATOR]");
    RoleModel caseworker = model.getRoles().stream()
        .filter(r -> r.getId().equals("caseworker-test")).findFirst().orElseThrow();
    assertThat(caseworker.getJavaConstant()).isEqualTo("CASEWORKER_TEST");
    assertThat(caseworker.getCaseTypePermissions()).isEqualTo("CRUD");
    assertThat(caseworker.isCaseRole()).isFalse();
    RoleModel creator = model.getRoles().stream()
        .filter(r -> r.getId().equals("[CREATOR]")).findFirst().orElseThrow();
    assertThat(creator.isCaseRole()).isTrue();
    assertThat(creator.getJavaConstant()).isEqualTo("CREATOR");
    assertThat(creator.getName()).isEqualTo("Creator");
  }

  @Test
  void buildsFixedListsWithSanitisedConstants() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "claimType", "Label", "Claim",
                "FieldType", "FixedList", "FieldTypeParameter", "ClaimType"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "money-claim",
                "ListElement", "Money claim", "DisplayOrder", 1))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "possession",
                "ListElement", "Possession", "DisplayOrder", 2))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getFixedLists()).singleElement()
        .extracting(FixedListModel::getId).isEqualTo("ClaimType");
    List<FixedListModel.Item> items = model.getFixedLists().get(0).getItems();
    assertThat(items).extracting(FixedListModel.Item::getJavaConstant)
        .containsExactly("MONEY_CLAIM", "POSSESSION");
    assertThat(gaps.getEntries())
        .anyMatch(g -> g.getCategory() == GapCategory.IDENTIFIER_SANITISED
            && "money-claim".equals(g.getValue()));
  }

  @Test
  void dedupesExactDuplicateFixedListsRows() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "claimType", "Label", "Claim",
                "FieldType", "FixedList", "FieldTypeParameter", "ClaimType"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "money-claim",
                "ListElement", "Money claim", "DisplayOrder", 1))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "money-claim",
                "ListElement", "Money claim", "DisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getFixedLists()).singleElement()
        .satisfies(fl -> assertThat(fl.getItems()).singleElement()
            .extracting(FixedListModel.Item::getCode).isEqualTo("money-claim"));
  }

  @Test
  void emitsBothItemsForSameFileDuplicateCodeWithDifferentLabels() {
    // A duplicate ListElementCode with a different label arriving from the SAME source file is a
    // genuine data quirk (e.g. ia's govUkNationalities lists 'CC' twice). The definition store
    // imports both rows, so a faithful round-trip must emit both — uniqued as CC and CC_2.
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "nationality", "Label", "Nationality",
                "FieldType", "FixedList", "FieldTypeParameter", "Nationality"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "Nationality", "ListElementCode", "CC",
                "ListElement", "Cocos (Keeling) Island"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "Nationality", "ListElementCode", "CC",
                "ListElement", "Cymro"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getFixedLists()).singleElement()
        .satisfies(fl -> {
          assertThat(fl.getItems()).extracting(FixedListModel.Item::getJavaConstant)
              .containsExactly("CC", "CC_2");
          assertThat(fl.getItems()).extracting(FixedListModel.Item::getCode)
              .containsExactly("CC", "CC");
          assertThat(fl.getItems()).extracting(FixedListModel.Item::getLabel)
              .containsExactly("Cocos (Keeling) Island", "Cymro");
        });
    assertThat(gaps.getEntries())
        .anyMatch(g -> g.getCategory() == GapCategory.IDENTIFIER_SANITISED
            && "Nationality/CC".equals(g.getRowKey()));
  }

  @Test
  void rejectsCrossFileConflictingFixedListsRowsWithDifferentLabels() {
    // The same code with different labels arriving from DIFFERENT files points at two unrelated
    // case types' lists colliding under one ID — ambiguous input that must be caught.
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "claimType", "Label", "Claim",
                "FieldType", "FixedList", "FieldTypeParameter", "ClaimType"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "money-claim",
                "ListElement", "Money claim"),
            java.nio.file.Path.of("caseA", "FixedLists.json"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "money-claim",
                "ListElement", "A different label"),
            java.nio.file.Path.of("caseB", "FixedLists.json"))
        .build();

    assertThatThrownBy(() -> linker.link(ir, options("Minimal"), gaps))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ClaimType");
  }

  @Test
  void rejectsConflictingComplexTypesMembersWithDifferentFieldType() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        // A CaseField references Outer so it is reachable and its members are built (unreferenced
        // complex types are passed through, not built, so the conflict would not be reached).
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "outer", "Label", "Outer",
                "FieldType", "Complex", "FieldTypeParameter", "Outer"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Outer", "ListElementCode", "name", "FieldType", "Text",
                "ElementLabel", "Name"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Outer", "ListElementCode", "name", "FieldType", "Number",
                "ElementLabel", "Name"))
        .build();

    assertThatThrownBy(() -> linker.link(ir, options("Minimal"), gaps))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Outer");
  }

  @Test
  void excludesFixedListsRowsScopedToAnotherCaseType() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "claimType", "Label", "Claim",
                "FieldType", "FixedList", "FieldTypeParameter", "ClaimType"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "money-claim",
                "ListElement", "Money claim"))
        .row(SheetName.FIXED_LISTS,
            cols("CaseTypeID", "OtherCaseType", "ID", "ClaimType",
                "ListElementCode", "possession", "ListElement", "Possession"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Pinning current rowsForCaseType behaviour: rows scoped to another case type via an
    // explicit CaseTypeID are excluded, even when other rows for the same list ID have none.
    assertThat(model.getFixedLists()).singleElement()
        .satisfies(fl -> assertThat(fl.getItems()).extracting(FixedListModel.Item::getCode)
            .containsExactly("money-claim"));
  }

  @Test
  void computesComplexTypeDepthAndSkipsPredefined() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        // A CaseField references Outer so the Outer->Inner graph is reachable and generated
        // (an unreferenced complex type is dropped with an advisory gap, not emitted as a class).
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "outer", "Label", "Outer",
                "FieldType", "Complex", "FieldTypeParameter", "Outer"))
        // Outer references Inner; Inner references nothing -> Outer depth 1, Inner depth 0.
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Outer", "ListElementCode", "inner", "FieldType", "Complex",
                "FieldTypeParameter", "Inner", "ElementLabel", "Inner"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Inner", "ListElementCode", "name", "FieldType", "Text",
                "ElementLabel", "Name"))
        // AddressUK is SDK-predefined and must be skipped.
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "AddressUK", "ListElementCode", "postcode", "FieldType", "Text"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getComplexTypes()).extracting(ComplexTypeModel::getId)
        .containsExactlyInAnyOrder("Outer", "Inner");
    Map<String, Integer> depthById = model.getComplexTypes().stream()
        .collect(java.util.stream.Collectors.toMap(ComplexTypeModel::getId, ComplexTypeModel::getDepth));
    assertThat(depthById.get("Inner")).isZero();
    assertThat(depthById.get("Outer")).isEqualTo(1);
    assertThat(gaps.getEntries())
        .anyMatch(g -> "ComplexTypes".equals(g.getSheet()) && "AddressUK".equals(g.getValue()));
  }

  @Test
  void orphanComplexTypeIsDroppedWithAdvisoryGapNotPassthrough() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        // No CaseField references Orphan, so it is unreachable: the SDK generates no class for it.
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "name", "Label", "Name", "FieldType", "Text"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Orphan", "ListElementCode", "field", "FieldType", "Text",
                "ElementLabel", "Field"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Dropped, not generated and not passed through — its absence is an accepted semantic difference.
    assertThat(model.getComplexTypes()).extracting(ComplexTypeModel::getId).doesNotContain("Orphan");
    assertThat(model.getPassthroughSheets())
        .noneMatch(s -> s.getRelativePath().contains("ComplexTypes/Orphan"));
    assertThat(gaps.getEntries()).anyMatch(g -> "ComplexTypes".equals(g.getSheet())
        && "Orphan".equals(g.getValue()) && g.getAction() == GapAction.ADVISORY);
  }

  @Test
  void predefinedComplexTypeRedeclarationIsDroppedWithAdvisoryGapNotPassthrough() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        // A field references the built-in Fee type; the input also spells Fee's members out. The
        // field resolves to the built-in class and the redundant member rows are dropped (advisory).
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "fee", "Label", "Fee",
                "FieldType", "Complex", "FieldTypeParameter", "Fee"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Fee", "ListElementCode", "calculatedAmount", "FieldType", "Text",
                "ElementLabel", "Amount"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getComplexTypes()).extracting(ComplexTypeModel::getId).doesNotContain("Fee");
    assertThat(model.getPassthroughSheets())
        .noneMatch(s -> s.getRelativePath().contains("ComplexTypes/Fee"));
    assertThat(gaps.getEntries()).anyMatch(g -> "ComplexTypes".equals(g.getSheet())
        && "Fee".equals(g.getValue()) && g.getAction() == GapAction.ADVISORY);
  }

  @Test
  void illegalIdComplexTypeIsGeneratedWithSanitisedClassNameAndRawIdCarrier() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        // prl's real case: a reachable complex type whose ID is not a legal Java identifier.
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "directions", "Label", "Directions",
                "FieldType", "Collection", "FieldTypeParameter", "schoolDirections&Details"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "schoolDirections&Details", "ListElementCode", "detail", "FieldType", "Text",
                "ElementLabel", "Detail"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Generated (not passthrough) under a sanitised PascalCase class name; the raw wire ID is
    // preserved on the model so ComplexTypeEmitter emits @ComplexType(name = "schoolDirections&Details").
    ComplexTypeModel generated = model.getComplexTypes().stream()
        .filter(ct -> "schoolDirections&Details".equals(ct.getId()))
        .findFirst()
        .orElseThrow();
    assertThat(generated.getJavaClassName()).isEqualTo("SchoolDirectionsDetails");
    assertThat(model.getPassthroughSheets())
        .noneMatch(s -> s.getRelativePath().contains("schoolDirections"));
    assertThat(gaps.getEntries()).anyMatch(g -> "ComplexTypes".equals(g.getSheet())
        && "schoolDirections&Details".equals(g.getValue())
        && g.getCategory() == GapCategory.IDENTIFIER_SANITISED);
  }

  @Test
  void orphanFixedListIsDroppedWithAdvisoryGapNotPassthrough() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        // No field references OrphanList; the SDK generates no enum for it.
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "name", "Label", "Name", "FieldType", "Text"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "OrphanList", "ListElementCode", "a", "ListElement", "A"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getFixedLists()).extracting(FixedListModel::getId).doesNotContain("OrphanList");
    assertThat(model.getPassthroughSheets())
        .noneMatch(s -> s.getRelativePath().contains("FixedLists/OrphanList"));
    assertThat(gaps.getEntries()).anyMatch(g -> "FixedLists".equals(g.getSheet())
        && "OrphanList".equals(g.getValue()) && g.getAction() == GapAction.ADVISORY);
  }

  @Test
  void illegalIdFixedListIsGeneratedWithSanitisedEnumNameAndRawIdCarrier() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        // fpl's real case: a referenced fixed list whose ID is not a legal Java identifier.
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "court", "Label", "Court",
                "FieldType", "FixedList", "FieldTypeParameter", "Stoke-on-TrentDFJCourts"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "Stoke-on-TrentDFJCourts", "ListElementCode", "338",
                "ListElement", "Stoke-on-Trent"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Generated as an enum (not passthrough) under a sanitised name; the raw list ID rides on the
    // model so EnumEmitter emits @ComplexType(name = "Stoke-on-TrentDFJCourts").
    FixedListModel generated = model.getFixedLists().stream()
        .filter(fl -> "Stoke-on-TrentDFJCourts".equals(fl.getId()))
        .findFirst()
        .orElseThrow();
    assertThat(IdentifierSanitiser.isLegalIdentifier(generated.getJavaClassName())).isTrue();
    assertThat(model.getPassthroughSheets())
        .noneMatch(s -> s.getRelativePath().contains("Stoke"));
  }

  @Test
  void mapsFieldTypesAndFlagsUnmappedColumnCarriedAsShowCondition() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "applicantName", "Label", "Name",
                "FieldType", "Text"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "applicantEmail", "Label", "Email",
                "FieldType", "Email"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "claimType", "Label", "Claim",
                "FieldType", "FixedList", "FieldTypeParameter", "ClaimType"))
        .row(SheetName.FIXED_LISTS,
            cols("ID", "ClaimType", "ListElementCode", "money", "ListElement", "Money"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    Map<String, FieldModel> byId = model.getCaseFields().stream()
        .collect(java.util.stream.Collectors.toMap(FieldModel::getId, f -> f));
    assertThat(byId.get("applicantName").getJavaType()).isEqualTo("String");
    assertThat(byId.get("applicantName").getTypeOverride()).isNull();
    assertThat(byId.get("applicantEmail").getTypeOverride()).isEqualTo("Email");
    assertThat(byId.get("claimType").getJavaType()).isEqualTo("ClaimType");
    assertThat(byId.get("claimType").getTypeOverride()).isEqualTo("FixedList");
    assertThat(byId.get("claimType").getTypeParameterOverride()).isEqualTo("ClaimType");
  }

  @Test
  void overlayOnlyCaseFieldWithConfiguredPredicateBecomesGatedMember() {
    GapCollector gaps = new GapCollector();
    // Two configured suffixes; only 'jo' matches an active predicate by default (CCD_DEF_JO unset,
    // so !CCD_DEF_JO:true is active). The overlay-only field is emitted once, as a gated member.
    ConversionOptions options = ConversionOptions.builder()
        .caseTypeId("Minimal")
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .overlaySuffixes(new java.util.LinkedHashMap<>(Map.of(
            "jo", OverlayCondition.parse("!CCD_DEF_JO:true"))))
        .build();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "applicantName", "Label", "Name",
                "FieldType", "Text"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "joField", "Label", "JO field",
                "FieldType", "Text"),
            Set.of("jo"))
        .build();

    CaseTypeModel model = linker.link(ir, options, gaps);

    Map<String, FieldModel> byId = model.getCaseFields().stream()
        .collect(java.util.stream.Collectors.toMap(FieldModel::getId, f -> f));
    // The base field is ungated; the overlay field carries the gate expression and NO overlay tags
    // (so CaseDataEmitter emits it as a real member the SDK gates at generation time).
    assertThat(byId.get("applicantName").getGate()).isNull();
    assertThat(byId.get("joField").getGate()).isEqualTo("!CCD_DEF_JO:true");
    assertThat(byId.get("joField").getOverlayTags()).isEmpty();
  }

  @Test
  void complementaryOverlayFragmentsWithSameFieldIdEmitOneGatedMember() {
    GapCollector gaps = new GapCollector();
    // Mirrors civil's JO layout: the same field ID appears under two complementary suffixes
    // (prod / nonprod). Exactly one gated member is emitted — the one whose predicate is active in
    // the convert-time environment (no env set → nonprod active) — deduped by ID.
    ConversionOptions options = ConversionOptions.builder()
        .caseTypeId("Minimal")
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .overlaySuffixes(new java.util.LinkedHashMap<>(Map.of(
            "prod", OverlayCondition.parse("CCD_DEF_ENV:prod"),
            "nonprod", OverlayCondition.parse("!CCD_DEF_ENV:prod"))))
        .build();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "joField", "Label", "JO field",
                "FieldType", "Text"),
            Set.of("prod"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "joField", "Label", "JO field",
                "FieldType", "Text"),
            Set.of("nonprod"))
        .build();

    CaseTypeModel model = linker.link(ir, options, gaps);

    List<FieldModel> joFields = model.getCaseFields().stream()
        .filter(f -> f.getId().equals("joField")).toList();
    assertThat(joFields).hasSize(1);
    assertThat(joFields.get(0).getGate()).isEqualTo("!CCD_DEF_ENV:prod");
  }

  @Test
  void assemblesEventsWithGrantsPreStatesAndCallbackGraft() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "DisplayOrder", 1, "PreConditionState(s)", "", "PostConditionState", "Open",
                "ShowSummary", "Y",
                "CallBackURLAboutToSubmitEvent", "${CCD_DEF_BASE_URL}/about-to-submit"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "addNotes", "Name", "Add notes",
                "DisplayOrder", 2, "PreConditionState(s)", "Open;Closed",
                "PostConditionState", "*"))
        .row(SheetName.AUTHORISATION_CASE_EVENT,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "UserRole", "caseworker-test", "CRUD", "CRUD"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    EventModel create = model.getEvents().stream()
        .filter(e -> e.getId().equals("createCase")).findFirst().orElseThrow();
    assertThat(create.getPreStates()).isEmpty();
    assertThat(create.getPostState()).isEqualTo("Open");
    assertThat(create.getShowSummary()).isTrue();
    assertThat(create.getGrants()).containsEntry("caseworker-test", "CRUD");
    // No callback wiring is modelled; the callback URL is carried verbatim (raw placeholder) via a
    // CaseEvent/<id>.json column-graft passthrough sheet keyed by the event ID.
    PassthroughSheet createGraft = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath().equals("CaseEvent/createCase.json"))
        .findFirst().orElseThrow();
    assertThat(createGraft.getRows()).anySatisfy(r ->
        assertThat(r).containsEntry("CallBackURLAboutToSubmitEvent",
            "${CCD_DEF_BASE_URL}/about-to-submit"));

    EventModel notes = model.getEvents().stream()
        .filter(e -> e.getId().equals("addNotes")).findFirst().orElseThrow();
    assertThat(notes.getPreStates()).containsExactly("Open", "Closed");
    assertThat(notes.getPostState()).isEqualTo("*");
  }

  @Test
  void readsCaseEventDescriptionVerbatimIncludingBlankAndTrailingWhitespace() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "blankDescription", "Name", "Blank description",
                "Description", " ", "PostConditionState", "*"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "trailingSpace", "Name", "Trailing space",
                "Description", "Update parent case data ", "PostConditionState", "*"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "noDescription", "Name", "No description",
                "PostConditionState", "*"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    EventModel blank = model.getEvents().stream()
        .filter(e -> e.getId().equals("blankDescription")).findFirst().orElseThrow();
    // A blank-but-authored Description (e.g. civil's CHECK_AND_MARK_PAID_IN_FULL) must be kept
    // verbatim rather than collapsed to null, or the generated builder would silently default it
    // to the event's Name (EventBuilder.name()).
    assertThat(blank.getDescription()).isEqualTo(" ");

    EventModel trailing = model.getEvents().stream()
        .filter(e -> e.getId().equals("trailingSpace")).findFirst().orElseThrow();
    assertThat(trailing.getDescription()).isEqualTo("Update parent case data ");

    EventModel noDescription = model.getEvents().stream()
        .filter(e -> e.getId().equals("noDescription")).findFirst().orElseThrow();
    assertThat(noDescription.getDescription()).isNull();
  }

  @Test
  void groupsPagesByPageIdAndAttachesComplexOverrides() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "applicantName", "DisplayContext", "MANDATORY",
                "PageID", "1", "PageLabel", "Details", "PageFieldDisplayOrder", 1,
                "PageDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "respondent", "DisplayContext", "COMPLEX",
                "PageID", "2", "PageFieldDisplayOrder", 1,
                "CallBackURLMidEvent", "${CCD_DEF_BASE_URL}/mid"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("CaseEventID", "createCase", "CaseFieldID", "respondent",
                "ListElement", "fullName", "DisplayContext", "MANDATORY"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    EventModel create = model.getEvents().get(0);
    assertThat(create.getPages()).extracting(PageModel::getPageId).containsExactly("1", "2");
    PageModel page1 = create.getPages().get(0);
    assertThat(page1.getLabel()).isEqualTo("Details");
    assertThat(page1.getFields()).singleElement()
        .extracting(PageModel.PageField::getDisplayContext).isEqualTo("MANDATORY");
    PageModel page2 = create.getPages().get(1);
    assertThat(page2.getFields().get(0).getComplexTypeOverrides()).containsKey("fullName");
    // The mid-event callback URL is not modelled on the page; it is carried verbatim (raw
    // placeholder) via the CaseEventToFields column-graft passthrough for the event.
    PassthroughSheet midEventGraft = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath().equals("CaseEventToFields/createCase.json"))
        .findFirst().orElseThrow();
    assertThat(midEventGraft.getRows()).anySatisfy(r ->
        assertThat(r).containsEntry("CallBackURLMidEvent", "${CCD_DEF_BASE_URL}/mid"));
  }

  @Test
  void eventToComplexTypesPassthroughKeysOnIdSoDistinctMembersDoNotCollide() {
    GapCollector gaps = new GapCollector();
    // Two distinct complex types (Child, OtherPerson) both nest under the same event/field
    // (childDetails/children) and both declare a member named "firstName". Without ID in the
    // passthrough merge key, mergeInto would treat these as the same row (matching on
    // CaseEventID+CaseFieldID+ListElementCode alone), silently dropping one and grafting its
    // columns onto the other — see prl's real-world "children" field, which nests both a Child
    // and an OtherPersonWhoLivesWithChild member sharing member names like "firstName"/"address".
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Child", "CaseEventID", "createCase", "CaseFieldID", "children",
                "ListElementCode", "firstName", "DisplayContext", "OPTIONAL"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "OtherPerson", "CaseEventID", "createCase", "CaseFieldID", "children",
                "ListElementCode", "firstName", "DisplayContext", "MANDATORY"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    PassthroughSheet sheet = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath().equals("CaseEventToComplexTypes/createCase/children.json"))
        .findFirst().orElseThrow();
    assertThat(sheet.getPrimaryKeys()).contains("ID");
    assertThat(sheet.getRows()).hasSize(2);
    assertThat(sheet.getRows()).anySatisfy(r -> {
      assertThat(r).containsEntry("ID", "Child");
      assertThat(r).containsEntry("DisplayContext", "OPTIONAL");
    });
    assertThat(sheet.getRows()).anySatisfy(r -> {
      assertThat(r).containsEntry("ID", "OtherPerson");
      assertThat(r).containsEntry("DisplayContext", "MANDATORY");
    });
  }

  @Test
  void eventToComplexTypesPassthroughKeysOnShowConditionSoRepeatedMembersDoNotCollide() {
    GapCollector gaps = new GapCollector();
    // civil's real ORDER_REVIEW_OBLIGATION_CHECK/obligationWAFlag: the SAME member is listed twice
    // under the same declaring type -- once OPTIONAL with a show condition, once MANDATORY without.
    // ID cannot separate them (it is identical), so without FieldShowCondition in the merge key
    // mergeInto matches the two rows and silently drops the MANDATORY one. Measured across the six
    // review lanes, adding it takes significant row loss from civil 4 / prl 7 down to civil 1 / prl 2.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "check", "Name", "Check",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "ObligationData", "CaseEventID", "check", "CaseFieldID", "obligationWAFlag",
                "ListElementCode", "obligationReason", "DisplayContext", "OPTIONAL",
                "FieldShowCondition", "obligationWAFlagReason=\"OTHER\""))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "ObligationData", "CaseEventID", "check", "CaseFieldID", "obligationWAFlag",
                "ListElementCode", "obligationReason", "DisplayContext", "MANDATORY"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    PassthroughSheet sheet = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath()
            .equals("CaseEventToComplexTypes/check/obligationWAFlag.json"))
        .findFirst().orElseThrow();
    assertThat(sheet.getPrimaryKeys())
        .as("ID stays (it separates distinct declaring types) and FieldShowCondition joins it")
        .containsExactly("ID", "CaseEventID", "CaseFieldID", "ListElementCode",
            "FieldShowCondition");
    assertThat(sheet.getRows()).hasSize(2);
    assertThat(sheet.getRows()).anySatisfy(r -> {
      assertThat(r).containsEntry("DisplayContext", "OPTIONAL");
      assertThat(r).containsEntry("FieldShowCondition", "obligationWAFlagReason=\"OTHER\"");
    });
    assertThat(sheet.getRows()).anySatisfy(r ->
        assertThat(r).containsEntry("DisplayContext", "MANDATORY"));
  }

  @Test
  void derivesHintTriStateForComplexMemberOverrides() {
    GapCollector gaps = new GapCollector();
    // A complex field placed COMPLEX on an event, whose complex type declares two members with a
    // @CCD(hint) (role, reference) and one without (name). Three ETOCT rows exercise the tri-state:
    //   - role: input HintText equals the declared hint          → cascade (no override emitted)
    //   - reference: input carries no HintText but member declares one → .noHintText()
    //   - name: input carries a HintText, member declares none    → .hintText(value)
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "role", "FieldType", "Text",
                "HintText", "The declared role hint"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "reference", "FieldType", "Text",
                "HintText", "The declared reference hint"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1",
                "PageFieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY",
                "HintText", "An overriding hint", "FieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "role", "DisplayContext", "OPTIONAL",
                "HintText", "The declared role hint", "FieldDisplayOrder", 2))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "reference", "DisplayContext", "READONLY",
                "FieldDisplayOrder", 3))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCasecontact");
    assertThat(group).as("the whole group derives (no fallback for a hint mismatch)").isNotNull();
    assertThat(group.getRootElementType()).as("a scalar complex root has no element type").isNull();

    var byGetter = new java.util.HashMap<String, uk.gov.hmcts.ccd.sdk.converter.model
        .EventComplexTypeGroup.Member>();
    group.getMembers().forEach(m -> byGetter.put(m.getLeafGetter(), m));

    // role: input HintText == declared hint → cascade, no override.
    assertThat(byGetter.get("getRole").isHintOverridden()).isFalse();
    // name: input HintText present, no declared hint → .hintText(value).
    assertThat(byGetter.get("getName").isHintOverridden()).isTrue();
    assertThat(byGetter.get("getName").getHintText()).isEqualTo("An overriding hint");
    // reference: no input HintText, declared hint present → .noHintText() (overridden, null value).
    assertThat(byGetter.get("getReference").isHintOverridden()).isTrue();
    assertThat(byGetter.get("getReference").getHintText()).isNull();
  }

  @Test
  void derivesRetainHiddenValueAndLeavesNoGraftForShowSummaryChangeOption() {
    GapCollector gaps = new GapCollector();
    // sscs's residual CaseEventToComplexTypes tail, both columns on one group:
    //   - RetainHiddenValue: the importer DOES read it on this sheet (EventCaseFieldComplexTypeParser
    //     maps it), and the SDK writes it from the same applyMetadata the CaseEventToFields rows use,
    //     so a Y row derives via .retainHiddenValue() and an N row leaves the flag unset.
    //   - ShowSummaryChangeOption: EventCaseFieldParser reads it on CaseEventToFields, but the
    //     complex-type parser never does — importer-ignored here, like ID.
    // Neither may leave a passthrough carrier behind: that would be the whole point of deriving them.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "role", "FieldType", "Text"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "reference", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1",
                "PageFieldDisplayOrder", 1))
        // Yes → derived as .retainHiddenValue(); also carries the importer-ignored SSCO=Y.
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY",
                "FieldShowCondition", "role=\"x\"", "RetainHiddenValue", "Yes",
                "ShowSummaryChangeOption", "Y", "FieldDisplayOrder", 1))
        // No → the flag stays unset, matching the generator's write-only-when-true behaviour.
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "role", "DisplayContext", "OPTIONAL",
                "RetainHiddenValue", "No", "FieldDisplayOrder", 2))
        // SSCO=N alone: nothing to derive and nothing to graft — the row leaves no carrier at all.
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "reference", "DisplayContext", "READONLY",
                "ShowSummaryChangeOption", "N", "FieldDisplayOrder", 3))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCasecontact");
    assertThat(group).as("all three rows derive").isNotNull();
    assertThat(group.getMembers()).hasSize(3);

    var byGetter = new java.util.HashMap<String, uk.gov.hmcts.ccd.sdk.converter.model
        .EventComplexTypeGroup.Member>();
    group.getMembers().forEach(m -> byGetter.put(m.getLeafGetter(), m));

    assertThat(byGetter.get("getName").isRetainHiddenValue())
        .as("RetainHiddenValue=Yes derives the flag").isTrue();
    assertThat(byGetter.get("getRole").isRetainHiddenValue())
        .as("RetainHiddenValue=No leaves the flag unset, so no column is written").isFalse();
    assertThat(byGetter.get("getReference").isRetainHiddenValue()).isFalse();

    // Neither column leaves a passthrough carrier: no CaseEventToComplexTypes file is written at all.
    assertThat(model.getPassthroughSheets())
        .noneMatch(s -> s.getRelativePath().contains("CaseEventToComplexTypes"));
  }

  @Test
  void leavesNoGraftForCaseTypeIdOnComplexTypeRows() {
    GapCollector gaps = new GapCollector();
    // sscs's last CaseEventToComplexTypes file (caseUpdated/appeal) existed solely to carry
    // "CaseTypeID": "Benefit" on one of the case type's 746 rows — evidently copied down from a
    // spreadsheet template. The importer never reads a case type off this sheet:
    // EventParser.parseCaseEventComplexTypes groups the rows by (CaseEventID, CaseFieldID) alone and
    // the complex-type parser never touches ColumnName.CASE_TYPE_ID; ColumnName.isRequired has no
    // CASE_EVENT_TO_COMPLEX_TYPES branch at all, so unlike CaseEvent/CaseField/CaseEventToFields the
    // column is not even required here. So it must not hold a whole verbatim file alive.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1",
                "PageFieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "ListElementCode", "name", "DisplayContext", "MANDATORY",
                "FieldDisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCasecontact");
    assertThat(group).as("the row derives").isNotNull();
    assertThat(group.getMembers()).hasSize(1);

    assertThat(model.getPassthroughSheets())
        .as("CaseTypeID alone leaves no carrier: no CaseEventToComplexTypes file is written")
        .noneMatch(s -> s.getRelativePath().contains("CaseEventToComplexTypes"));
  }

  @Test
  void leavesNoGraftForWizardPageColumnsOnComplexTypeRows() {
    GapCollector gaps = new GapCollector();
    // sscs's other last CaseEventToComplexTypes file
    // (writeFinalDecision/otherPartyAttendedQuestions) existed solely to carry the wizard-page trio
    // PageLabel/PageDisplayOrder/PageFieldDisplayOrder on two of the case type's 746 rows. Those
    // columns have exactly one reader in the importer — WizardPageParser, whose constructor pins
    // sheetName = SheetName.CASE_EVENT_TO_FIELDS alongside displayGroupLabel = PAGE_LABEL etc. — so a
    // page's label and ordering come from the event's CaseEventToFields rows and a complex-type member
    // row carrying them changes nothing on import. A member's own position comes from
    // FieldDisplayOrder, which is derived. So they must not hold a whole verbatim file alive either.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1",
                "PageLabel", "Type of hearing", "PageFieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 1,
                "PageLabel", "Type of hearing", "PageDisplayOrder", 3, "PageFieldDisplayOrder", 4))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCasecontact");
    assertThat(group).as("the row derives").isNotNull();
    assertThat(group.getMembers()).hasSize(1);

    assertThat(model.getPassthroughSheets())
        .as("the page trio alone leaves no carrier: no CaseEventToComplexTypes file is written")
        .noneMatch(s -> s.getRelativePath().contains("CaseEventToComplexTypes"));
  }

  @Test
  void derivesCollectionRootedComplexMemberGroup() {
    GapCollector gaps = new GapCollector();
    // A Collection-typed CaseField placed COMPLEX on an event, whose element type's members are
    // overridden per event. The group now derives (rather than falling back) with the root element
    // type recorded so the emitter opens the two-arg element-typed scope.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "parties", "Label", "Parties",
                "FieldType", "Collection", "FieldTypeParameter", "Party"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Party", "ListElementCode", "partyName", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "parties", "DisplayContext", "COMPLEX", "PageID", "1",
                "PageFieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Party", "CaseEventID", "createCase", "CaseFieldID", "parties",
                "ListElementCode", "partyName", "DisplayContext", "MANDATORY",
                "FieldDisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCaseparties");
    assertThat(group).as("a collection-rooted group now derives").isNotNull();
    assertThat(group.getRootElementType()).isNotNull();
    assertThat(group.getRootElementType().getSimpleName()).isEqualTo("Party");
    assertThat(group.getMembers()).singleElement()
        .satisfies(m -> assertThat(m.getLeafGetter()).isEqualTo("getPartyName"));
  }

  @Test
  void derivedGroupWithNoExoticTailLeavesNoPassthroughCarrier() {
    GapCollector gaps = new GapCollector();
    // A fully-derivable group whose rows carry only generator-computed columns (LEC, DisplayContext)
    // plus the two accepted-difference columns (ID, FieldDisplayOrder). The importer ignores ID on
    // this sheet and the SDK re-derives FieldDisplayOrder, so neither is grafted; with nothing else
    // to carry, the group emits its .complex(...) chain and NO passthrough file at all.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getEventComplexTypeGroups().get("createCasecontact"))
        .as("the group derives to a .complex(...) chain").isNotNull();
    assertThat(model.getPassthroughSheets())
        .as("no companion carrier is emitted when the row has no exotic tail")
        .noneMatch(p -> p.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/contact.json"));
  }

  @Test
  void derivedGroupGraftsOnlyTheExoticTailAdditively() {
    GapCollector gaps = new GapCollector();
    // Same derivable group, but one row also carries an exotic tail column the generator never writes
    // (SecurityClassification). Only that column is grafted -- not ID, not FieldDisplayOrder -- keyed
    // on the generator-emitted columns, additively (no overwriteColumns).
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 1,
                "SecurityClassification", "Private"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getEventComplexTypeGroups().get("createCasecontact")).isNotNull();
    PassthroughSheet sheet = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/contact.json"))
        .findFirst().orElseThrow();
    assertThat(sheet.getOverwriteColumns())
        .as("the tail is grafted additively, never overwriting a generated value").isEmpty();
    assertThat(sheet.getPrimaryKeys())
        .containsExactly("CaseEventID", "CaseFieldID", "ListElementCode", "FieldShowCondition");
    assertThat(sheet.getRows()).singleElement().satisfies(r -> {
      assertThat(r).containsEntry("SecurityClassification", "Private");
      assertThat(r).containsKeys("CaseEventID", "CaseFieldID", "ListElementCode");
      assertThat(r).as("ID is an importer-ignored accepted difference, not grafted")
          .doesNotContainKey("ID");
      assertThat(r).as("FieldDisplayOrder joins the display-order disposition, not grafted")
          .doesNotContainKey("FieldDisplayOrder");
      assertThat(r).as("a key column the generated row also omits stays ABSENT, not blank:"
              + " mergeInto's matcher counts absent-on-both as agreement but blank-vs-absent as a"
              + " mismatch, so a blank here would orphan the graft")
          .doesNotContainKey("FieldShowCondition");
    });
  }

  @Test
  void derivedGraftCarriesShowConditionAsAKeyWhenTheRowHasOne() {
    GapCollector gaps = new GapCollector();
    // FieldShowCondition is a merge key on this sheet (it is what separates the same-ListElementCode
    // rows real definitions ship -- civil's obligationWAFlag repeats each member as
    // OPTIONAL-with-show-condition and again as MANDATORY). The generator writes it from the builder
    // chain, so when the input row carries one the graft must too, or the merge orphans.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY",
                "FieldShowCondition", "contactName=\"y\"", "SecurityClassification", "Private"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getEventComplexTypeGroups().get("createCasecontact")).isNotNull();
    PassthroughSheet sheet = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/contact.json"))
        .findFirst().orElseThrow();
    assertThat(sheet.getRows()).singleElement().satisfies(r ->
        assertThat(r).containsEntry("FieldShowCondition", "contactName=\"y\""));
  }

  @Test
  void unresolvableMemberFallsBackAloneWithoutDraggingItsSiblingsAlong() {
    GapCollector gaps = new GapCollector();
    // Two members under one derivable (event, field): "name" resolves through the complex-type graph,
    // "mystery" does not (no such member on Contact). Every gate used to refuse the WHOLE group, so one
    // unresolvable member sent all its siblings to the verbatim passthrough -- across the six review
    // lanes that cost 5609 passthrough rows where 1408 was enough. Now only the unresolvable row falls
    // back and its siblings still derive into .complex(...) calls.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 1,
                "SecurityClassification", "Private"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "mystery", "DisplayContext", "OPTIONAL", "FieldDisplayOrder", 2))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().values().stream()
        .filter(g -> "contact".equals(g.getCaseFieldId())).findFirst().orElseThrow();
    assertThat(group.getMembers())
        .as("the resolvable member derives; the unresolvable one is simply absent")
        .singleElement().satisfies(m -> assertThat(m.getLeafGetter()).isEqualTo("getName"));

    // ONE sheet for the path, never two: GapAndPassthroughWriter keys both its output file and its
    // manifest entry on the relative path, so a second sheet would overwrite the first and the
    // manifest would then merge the survivor twice.
    assertThat(model.getPassthroughSheets())
        .filteredOn(s -> s.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/contact.json"))
        .hasSize(1);
    PassthroughSheet sheet = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/contact.json"))
        .findFirst().orElseThrow();
    assertThat(sheet.getPrimaryKeys())
        .as("a verbatim row shares the file, so ID rejoins the key -- the graft rows are unaffected"
            + " because neither they nor the generated rows they land on carry one")
        .containsExactly("ID", "CaseEventID", "CaseFieldID", "ListElementCode",
            "FieldShowCondition");
    assertThat(sheet.getRows()).hasSize(2);
    assertThat(sheet.getRows()).anySatisfy(r -> {
      assertThat(r).as("the derived member's tail-graft carries only the exotic column")
          .containsEntry("SecurityClassification", "Private");
      assertThat(r).containsEntry("ListElementCode", "name");
      assertThat(r).doesNotContainKey("ID");
    });
    assertThat(sheet.getRows()).anySatisfy(r -> {
      assertThat(r).as("the unresolvable member is passed through verbatim, ID included")
          .containsEntry("ID", "Contact");
      assertThat(r).containsEntry("ListElementCode", "mystery");
      assertThat(r).containsEntry("DisplayContext", "OPTIONAL");
    });
    assertThat(gaps.getEntries())
        .filteredOn(e -> "EventToComplexTypes".equals(e.getSheet()))
        .singleElement()
        .satisfies(e -> assertThat(e.getValue()).isEqualTo("1 derived / 1 passthrough"));
  }

  @Test
  void repeatedMemberUnderDivergentShowConditionsDerives() {
    GapCollector gaps = new GapCollector();
    // civil's ORDER_REVIEW_OBLIGATION_CHECK/obligationWAFlag shape: the same member placed twice, once
    // OPTIONAL with a show condition and once MANDATORY without. This IS expressible -- the SDK's
    // FieldCollection.createField appends unconditionally (no dedupe) and
    // CaseEventToComplexTypesGenerator merges on the show condition -- so the duplicate gate keys on
    // (ListElementCode, FieldShowCondition), not ListElementCode alone, and both placements derive.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "check", "Name", "Check",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "check",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "check", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "OPTIONAL",
                "FieldShowCondition", "contactName=\"OTHER\"", "FieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "check", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 2))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().values().stream()
        .filter(g -> "contact".equals(g.getCaseFieldId())).findFirst().orElseThrow();
    assertThat(group.getMembers()).hasSize(2);
    assertThat(group.getMembers()).allSatisfy(m ->
        assertThat(m.getLeafGetter()).isEqualTo("getName"));
    assertThat(group.getMembers()).extracting("showCondition")
        .containsExactly("contactName=\"OTHER\"", null);
    assertThat(model.getPassthroughSheets())
        .as("both placements derive, and neither row carries an exotic tail to graft")
        .noneMatch(s -> s.getRelativePath()
            .equals("CaseEventToComplexTypes/check/contact.json"));
  }

  @Test
  void twoRowsAgreeingOnTheGeneratorKeyWithDivergentContentRefuseTheWholeGroup() {
    GapCollector gaps = new GapCollector();
    // The one refusal that stays group-wide: two rows agreeing on BOTH (ListElementCode,
    // FieldShowCondition) -- the generator's own merge key for the sheet -- yet differing in content.
    // They would collapse into one generated row, and a colliding row kept as a passthrough would merge
    // ONTO the derived row (same key, and a row need not carry an ID to separate them) instead of
    // standing alongside it. So the group derives nothing and every row is passed through verbatim.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "contact", "DisplayContext", "COMPLEX", "PageID", "1"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "OPTIONAL", "FieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 2))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getEventComplexTypeGroups())
        .as("a divergent collision on the generator's key is not per-row recoverable").isEmpty();
    PassthroughSheet sheet = model.getPassthroughSheets().stream()
        .filter(s -> s.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/contact.json"))
        .findFirst().orElseThrow();
    assertThat(sheet.getRows()).as("both rows survive verbatim, ID and display order included")
        .hasSize(2);
    assertThat(sheet.getRows()).allSatisfy(r -> assertThat(r).containsEntry("ID", "Contact"));
    assertThat(gaps.getEntries())
        .filteredOn(e -> "EventToComplexTypes".equals(e.getSheet()))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.getValue()).isEqualTo("0 derived / 2 passthrough");
          // The cause tally, not the static prose (which names every cause) -- so this pins that the
          // refusal was attributed to the collision rather than to some other gate.
          assertThat(e.getDetail())
              .contains("2 row(s) — duplicate ListElementCode with divergent content");
        });
  }

  @Test
  void dedupesAccessClassesAndAccountsForInjectedEventGrants() {
    GapCollector gaps = new GapCollector();
    // The converter emits .explicitGrants() on every event, so an event's role grant does NOT
    // cascade onto the fields it places (see deriveAccessClasses): the SDK derives a non-immutable
    // field's CRUD solely from its @CCD(access=...) classes. So both caseworker (wants CRUD) and
    // citizen (wants R) need access-class grants; a and b share the same residual so a single
    // deduplicated class carries {caseworker=CRUD, citizen=R}.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "b", "Label", "B", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "a", "DisplayContext", "MANDATORY", "PageID", "1"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "b", "DisplayContext", "MANDATORY", "PageID", "1"))
        .row(SheetName.AUTHORISATION_CASE_EVENT,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "UserRole", "caseworker", "CRUD", "CRUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "caseworker",
                "CRUD", "CRUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "citizen", "CRUD", "R"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "b", "UserRole", "caseworker",
                "CRUD", "CRUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "b", "UserRole", "citizen", "CRUD", "R"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Both fields have identical residual {caseworker=CRUD, citizen=R}. Two fields do not clear the
    // group-mining threshold (>=3 fields), so the residual decomposes into its two atom classes; the
    // union of a field's atoms reproduces its residual, and both fields reference the same two atoms.
    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder("CaseworkerCrudAccess", "CitizenRAccess");
    assertThat(model.getCaseFields()).allSatisfy(f ->
        assertThat(f.getAccessClassNames())
            .containsExactlyInAnyOrder("CaseworkerCrudAccess", "CitizenRAccess"));
  }

  @Test
  void flagsAuthNotDerivableWhenInjectionExceedsGrant() {
    GapCollector gaps = new GapCollector();
    // The SDK unconditionally injects read (R) for every field on an unrestricted tab, for every
    // role that already holds a field grant. Here field a is on an unrestricted tab and caseworker
    // holds a grant (via field b's event), yet the definition gives caseworker no grant at all on a
    // -> the injected R exceeds the (empty) desired grant, which no access class can subtract, so
    // it is flagged AUTH_NOT_DERIVABLE. (An event grant alone no longer over-injects: the converter
    // emits .explicitGrants(), so event grants do not cascade onto fields.)
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "b", "Label", "B", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "b", "DisplayContext", "MANDATORY", "PageID", "1"))
        .row(SheetName.CASE_TYPE_TAB,
            cols("CaseTypeID", "Minimal", "TabID", "overview", "TabLabel", "Overview",
                "CaseFieldID", "a", "TabFieldDisplayOrder", "1"))
        .row(SheetName.AUTHORISATION_CASE_EVENT,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "UserRole", "caseworker", "CRUD", "CRUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "b", "UserRole", "caseworker",
                "CRUD", "CRUD"))
        .build();

    linker.link(ir, options("Minimal"), gaps);

    assertThat(gaps.getEntries())
        .anyMatch(g -> g.getCategory() == GapCategory.AUTH_NOT_DERIVABLE
            && g.getRowKey().equals("a/caseworker"));
  }

  @Test
  void producesOverlayVariantEventWithCondition() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "archiveCase", "Name", "Archive",
                "PostConditionState", "*"),
            java.util.Set.of("prod"))
        .build();

    CaseTypeModel model = linker.link(ir, optionsWithOverlay(), gaps);

    EventModel archive = model.getEvents().stream()
        .filter(e -> e.getId().equals("archiveCase")).findFirst().orElseThrow();
    assertThat(archive.getOverlaySuffix()).isEqualTo("prod");
    assertThat(archive.getOverlayCondition()).isNotNull();
    assertThat(archive.getOverlayCondition().getEnvVar()).isEqualTo("CCD_DEF_ENV");
    assertThat(archive.getJavaName()).isEqualTo("archiveCase_prod");
  }

  @Test
  void flagsOverlayEventWithoutConfiguredSuffix() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "archiveCase", "Name", "Archive",
                "PostConditionState", "*"),
            java.util.Set.of("experimental"))
        .build();
    ConversionOptions options = ConversionOptions.builder()
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .build();

    linker.link(ir, options, gaps);

    assertThat(gaps.getEntries())
        .anyMatch(g -> g.getCategory() == GapCategory.OVERLAY_NOT_EXPRESSIBLE);
  }

  @Test
  void bannerIsEmittedOnTheModelNotPassthrough() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.BANNER,
            cols("CaseTypeID", "Minimal", "BannerEnabled", "Yes", "BannerDescription", "Hi",
                "BannerUrl", "https://x", "BannerUrlText", "click"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Banner is reproduced via builder.banner(...) (see CoreConfigEmitter), so it is on the model
    // and produces no passthrough sheet.
    assertThat(model.getBanner()).isNotNull();
    assertThat(model.getBanner().isEnabled()).isTrue();
    assertThat(model.getBanner().getDescription()).isEqualTo("Hi");
    assertThat(model.getBanner().getUrl()).isEqualTo("https://x");
    assertThat(model.getBanner().getUrlText()).isEqualTo("click");
    assertThat(model.getPassthroughSheets())
        .noneMatch(p -> "Banner.json".equals(p.getRelativePath()));
  }

  @Test
  void passesThroughRawRowSheets() {
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.SEARCH_CRITERIA,
            cols("CaseTypeID", "Minimal", "OtherCaseReference", "ref"))
        .row(SheetName.CATEGORY,
            cols("CaseTypeID", "Minimal", "CategoryID", "docs", "CategoryLabel", "Documents"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), new GapCollector());

    assertThat(model.getSearchCriteria()).hasSize(1);
    assertThat(model.getCategories()).hasSize(1);
  }

  @Test
  void unsupportedWholeSheetsFailAsBlockingGapsWithNoPassthrough() {
    // SearchAlias/UserProfile/AccessType/AccessTypeRole have no SDK API; they are no longer carried
    // as raw-JSON passthrough — each becomes a blocking OMITTED_FAIL gap so a definition carrying one
    // fails conversion unless --allow-gaps.
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.SEARCH_ALIAS, cols("CaseTypeID", "Minimal", "SearchAliasFieldID", "ref"))
        .row(SheetName.USER_PROFILE, cols("UserIDAMId", "a@b.com", "WorkBasketDefaultState", "Open"))
        .row(SheetName.ACCESS_TYPE, cols("CaseTypeID", "Minimal", "AccessTypeID", "at"))
        .row(SheetName.ACCESS_TYPE_ROLE,
            cols("CaseTypeID", "Minimal", "AccessTypeID", "at", "RoleName", "r"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getPassthroughSheets())
        .noneMatch(p -> p.getRelativePath().matches(
            "SearchAlias\\.json|UserProfile\\.json|AccessType\\.json|AccessTypeRole\\.json"));
    assertThat(gaps.hasBlockingGaps()).isTrue();
    for (String sheet : List.of("SearchAlias", "UserProfile", "AccessType", "AccessTypeRole")) {
      assertThat(gaps.getEntries())
          .as("OMITTED_FAIL gap for " + sheet)
          .anyMatch(g -> sheet.equals(g.getSheet())
              && g.getAction() == GapAction.OMITTED_FAIL
              && g.getCategory() == GapCategory.UNSUPPORTED_SHEET);
    }
  }

  @Test
  void absentUnsupportedSheetsRecordNoGap() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal").build();

    linker.link(ir, options("Minimal"), gaps);

    assertThat(gaps.hasBlockingGaps()).isFalse();
  }

  @Test
  void mixedCaseRolesJurisdictionFailsAsBlockingGapWithNoGraft() {
    // emitCaseRoleJurisdiction() is all-or-nothing: when only SOME CaseRoles rows carry a
    // JurisdictionID it can't be used, and the mixed usage is now a blocking OMITTED_FAIL gap rather
    // than a per-row column graft.
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_ROLE,
            cols("CaseTypeID", "Minimal", "ID", "[CLAIMANT]", "JurisdictionID", "TEST"))
        .row(SheetName.CASE_ROLE, cols("CaseTypeID", "Minimal", "ID", "[DEFENDANT]"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.isEmitCaseRoleJurisdiction()).isFalse();
    assertThat(model.getPassthroughSheets())
        .noneMatch(p -> "CaseRoles.json".equals(p.getRelativePath()));
    assertThat(gaps.getEntries())
        .anyMatch(g -> "CaseRoles".equals(g.getSheet())
            && g.getAction() == GapAction.OMITTED_FAIL);
  }

  @Test
  void allRowsCaseRolesJurisdictionStillEmitsNativelyWithNoGap() {
    // When EVERY CaseRoles row carries a JurisdictionID the native switch reproduces it and no gap
    // is recorded — the deletion of the mixed-usage graft must not disturb this path.
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_ROLE,
            cols("CaseTypeID", "Minimal", "ID", "[CLAIMANT]", "JurisdictionID", "TEST"))
        .row(SheetName.CASE_ROLE,
            cols("CaseTypeID", "Minimal", "ID", "[DEFENDANT]", "JurisdictionID", "TEST"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.isEmitCaseRoleJurisdiction()).isTrue();
    assertThat(gaps.getEntries())
        .noneMatch(g -> "CaseRoles".equals(g.getSheet()));
  }

  @Test
  void unknownFieldTypeFailsAsBlockingGapWithNoGraft() {
    // A type with no Java carrier that is NOT a real FieldType constant can only be generated as
    // String → FieldType=Text. It is no longer grafted back; it becomes a blocking OMITTED_FAIL gap.
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "queries", "Label", "Queries",
                "FieldType", "SomeBespokeWidget"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // The field is generated as String (the SDK cannot express the type); no CaseField overwrite
    // graft is produced (attachAccessClasses rebuilds the model fields without the unknownType flag,
    // so the gap — not the field flag — is the observable signal).
    assertThat(model.getCaseFields()).singleElement()
        .satisfies(f -> assertThat(f.getJavaType()).isEqualTo("String"));
    assertThat(model.getPassthroughSheets())
        .noneMatch(p -> "CaseField.json".equals(p.getRelativePath()));
    assertThat(gaps.hasBlockingGaps()).isTrue();
    assertThat(gaps.getEntries())
        .anyMatch(g -> "CaseField".equals(g.getSheet())
            && "FieldType".equals(g.getColumn())
            && "SomeBespokeWidget".equals(g.getValue())
            && g.getAction() == GapAction.OMITTED_FAIL);
  }

  @Test
  void conditionalPostStateCollapsesToPrimaryAndRecordsConditionalCodeGap() {
    // A conditional/multi post-state is no longer grafted: the event ends in the primary state and
    // the loss is recorded as a CONDITIONAL_CODE gap (team must reimplement via aboutToSubmit).
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.STATE, cols("CaseTypeID", "Minimal", "ID", "started", "Name", "Started"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "startAppeal", "Name", "Start",
                "PreConditionState(s)", "", "PostConditionState",
                "started(isAdmin=\"Yes\"):2;other"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    EventModel event = model.getEvents().stream()
        .filter(e -> "startAppeal".equals(e.getId())).findFirst().orElseThrow();
    assertThat(event.getPostState()).isEqualTo("started");
    // No PostConditionState overwrite graft is produced for the event.
    assertThat(model.getPassthroughSheets())
        .filteredOn(p -> "CaseEvent/startAppeal.json".equals(p.getRelativePath()))
        .allSatisfy(p -> assertThat(p.getOverwriteColumns()).isEmpty());
    assertThat(gaps.getEntries())
        .anyMatch(g -> "CaseEvent".equals(g.getSheet())
            && "PostConditionState".equals(g.getColumn())
            && g.getAction() == GapAction.CONDITIONAL_CODE);
  }

  @Test
  void tabsCarryFieldsAndRoleRestriction() {
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_TYPE_TAB,
            cols("CaseTypeID", "Minimal", "TabID", "summary", "TabLabel", "Summary",
                "TabDisplayOrder", 1, "CaseFieldID", "a", "TabFieldDisplayOrder", 1,
                "UserRole", "caseworker"))
        .row(SheetName.CASE_TYPE_TAB,
            cols("CaseTypeID", "Minimal", "TabID", "summary", "TabLabel", "Summary",
                "CaseFieldID", "b", "TabFieldDisplayOrder", 2))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), new GapCollector());

    assertThat(model.getTabs()).singleElement().satisfies(tab -> {
      assertThat(tab.getTabId()).isEqualTo("summary");
      assertThat(tab.getUserRole()).isEqualTo("caseworker");
      assertThat(tab.getFields()).hasSize(2);
    });
  }

  @Test
  void reportsUnmappedGapEntriesArePresentInCollector() {
    GapCollector gaps = new GapCollector();
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.STATE, cols("CaseTypeID", "Minimal", "ID", "case-open", "Name", "Open"))
        .build();

    linker.link(ir, options("Minimal"), gaps);

    List<GapEntry> stateGaps = gaps.getEntries().stream()
        .filter(g -> "State".equals(g.getSheet())).toList();
    assertThat(stateGaps).anyMatch(g -> g.getCategory() == GapCategory.IDENTIFIER_SANITISED);
  }

  @Test
  void singleUseResidualEmittedAsAtomClass() {
    GapCollector gaps = new GapCollector();
    // A residual grant used by exactly one field is expressed as a named atom class referenced from
    // @CCD(access) — the composition scheme has no inline-grant path.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "caseworker-test",
                "CRUD", "CRU"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getAccessClasses()).singleElement()
        .satisfies(ac -> {
          assertThat(ac.getClassName()).isEqualTo("CaseworkerTestCruAccess");
          assertThat(ac.getGrants()).containsOnly(Map.entry("caseworker-test", "CRU"));
        });
    FieldModel field = model.getCaseFields().stream()
        .filter(f -> f.getId().equals("a")).findFirst().orElseThrow();
    assertThat(field.getAccessClassNames()).containsExactly("CaseworkerTestCruAccess");
  }

  @Test
  void sharedSingleRoleResidualReuseSameAtomClass() {
    GapCollector gaps = new GapCollector();
    // Fields sharing a single-role residual reference the same atom class (an atom carries its CRUD
    // in the name, so a CRU atom is CaseworkerTestCruAccess).
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "b", "Label", "B", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "caseworker-test",
                "CRUD", "CRU"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "b", "UserRole", "caseworker-test",
                "CRUD", "CRU"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getAccessClasses()).singleElement()
        .extracting(AccessClassModel::getClassName).isEqualTo("CaseworkerTestCruAccess");
    assertThat(model.getCaseFields()).allSatisfy(f ->
        assertThat(f.getAccessClassNames()).containsExactly("CaseworkerTestCruAccess"));
  }

  @Test
  void multiRoleResidualDecomposesIntoAtomClasses() {
    GapCollector gaps = new GapCollector();
    // A multi-role residual decomposes into one atom class per role (a group needs >=3 fields to
    // form, so with two fields the residual is covered by its two atoms). Each atom name carries its
    // role token and CRUD.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "b", "Label", "B", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "caseworker", "CRUD", "CRU"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "citizen", "CRUD", "R"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "b", "UserRole", "caseworker", "CRUD", "CRU"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "b", "UserRole", "citizen", "CRUD", "R"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder("CaseworkerCruAccess", "CitizenRAccess");
    // The union of a field's atom classes reproduces its residual.
    assertThat(model.getCaseFields()).allSatisfy(f ->
        assertThat(f.getAccessClassNames())
            .containsExactlyInAnyOrder("CaseworkerCruAccess", "CitizenRAccess"));
  }

  @Test
  void frequentAtomSetMinedIntoDefaultAccessGroup() {
    GapCollector gaps = new GapCollector();
    // Three fields share the atom-set {caseworker=CRU, citizen=R}; it clears the >=3 fields, >=2
    // atoms threshold, so it is mined into a group. As the most-used group it is named DefaultAccess.
    var builder = minimal("Minimal");
    for (String id : new String[] {"a", "b", "c"}) {
      builder.row(SheetName.CASE_FIELD,
          cols("CaseTypeID", "Minimal", "ID", id, "Label", id, "FieldType", "Text"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", id, "UserRole", "caseworker", "CRUD", "CRU"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", id, "UserRole", "citizen", "CRUD", "R"));
    }
    CaseTypeModel model = linker.link(builder.build(), options("Minimal"), gaps);

    assertThat(model.getAccessClasses()).singleElement()
        .satisfies(ac -> {
          assertThat(ac.getClassName()).isEqualTo("DefaultAccess");
          assertThat(ac.getGrants()).containsOnly(
              Map.entry("caseworker", "CRU"), Map.entry("citizen", "R"));
        });
    assertThat(model.getCaseFields()).allSatisfy(f ->
        assertThat(f.getAccessClassNames()).containsExactly("DefaultAccess"));
  }

  @Test
  void commonRolePrefixIsElidedFromAtomNames() {
    GapCollector gaps = new GapCollector();
    // Every role sharing the "caseworker-probate-" prefix carries no information (maintainer
    // directive): the derived atom names should drop it and keep only each role's remainder. A
    // third prefixed role clears the MIN_PREFIX_ROLES floor alongside the >50% share bar.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-rparobot", "CRUD", "CUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-systemupdate", "CRUD", "CU"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-caseadmin", "CRUD", "R"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Every maintainer-example shape resolves to a short, prefix-free name: no "CaseworkerProbate"
    // token survives in any atom.
    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder("RparobotCudAccess", "SystemupdateCuAccess", "CaseadminRAccess");
  }

  @Test
  void roleThatIsExactlyTheCommonPrefixKeepsItsLastToken() {
    GapCollector gaps = new GapCollector();
    // "caseworker-probate" IS the shared prefix "caseworker-probate" exactly (no remainder), so
    // per the documented rule it keeps its last hyphen token ("probate") rather than collapsing
    // to an empty name. A third prefixed role clears the MIN_PREFIX_ROLES floor alongside the
    // >50% share bar.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate", "CRUD", "R"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-caseadmin", "CRUD", "CRU"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-systemupdate", "CRUD", "CU"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder("ProbateRAccess", "CaseadminCruAccess", "SystemupdateCuAccess");
  }

  @Test
  void mixedPrefixCaseTypeStripsNothingBelowTheShareBar() {
    GapCollector gaps = new GapCollector();
    // Only 1 of 3 distinct roles shares "caseworker-probate-"; that is below both the >50% share
    // bar and the minimum-3-roles bar, so no prefix is common and every role keeps its full token
    // form.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-caseadmin", "CRUD", "CRU"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "citizen", "CRUD", "R"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole", "solicitor", "CRUD", "R"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder(
            "CaseworkerProbateCaseadminCruAccess", "CitizenRAccess", "SolicitorRAccess");
  }

  @Test
  void simpleMajorityPrefixCaseTypeStripsTheMajorityRoles() {
    GapCollector gaps = new GapCollector();
    // Probate-shaped: 11 of 18 distinct roles (61%) carry "caseworker-probate-" — a simple
    // majority but below a supermajority. That still clears the >50% bar (and the minimum-3-roles
    // bar), so the 11 prefixed roles are stripped to their remainder while the 7 non-prefixed
    // roles are left untouched. One role per field (distinct CRUD) so every residual stays within
    // MAX_CLASSES_PER_FIELD and is named as a standalone atom rather than a dedicated fallback.
    String[] prefixed = {
        "administrationteam", "approver", "caa", "systemupdate", "issuinganddebugadmin",
        "solicitor", "poarpasenior", "poarpaadmin", "exceptaide", "grantaide", "superuser",
    };
    String[] unprefixed = {
        "citizen", "letteredexecutor", "unletteredexecutor", "solicitorora",
        "caseworker-divorce-caseadmin", "caseworker-divorce-solicitor", "iac-legalrep-solicitor",
    };
    var builder = minimal("Minimal");
    int i = 0;
    for (String role : prefixed) {
      String fieldId = "f" + (i++);
      builder.row(SheetName.CASE_FIELD,
              cols("CaseTypeID", "Minimal", "ID", fieldId, "Label", fieldId, "FieldType", "Text"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", fieldId, "UserRole",
                  "caseworker-probate-" + role, "CRUD", "R"));
    }
    for (String role : unprefixed) {
      String fieldId = "f" + (i++);
      builder.row(SheetName.CASE_FIELD,
              cols("CaseTypeID", "Minimal", "ID", fieldId, "Label", fieldId, "FieldType", "Text"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", fieldId, "UserRole", role, "CRUD", "R"));
    }

    CaseTypeModel model = linker.link(builder.build(), options("Minimal"), gaps);

    List<String> names = model.getAccessClasses().stream()
        .map(AccessClassModel::getClassName).toList();
    // Prefixed roles: stripped, no "CaseworkerProbate" token survives.
    assertThat(names).noneMatch(n -> n.contains("CaseworkerProbate"));
    assertThat(names).contains("ApproverRAccess", "CaaRAccess", "SystemupdateRAccess");
    // Roles outside the prefix (including other case types' full namespaces) are untouched.
    assertThat(names).contains(
        "CitizenRAccess", "CaseworkerDivorceCaseadminRAccess", "IacLegalrepSolicitorRAccess");
  }

  @Test
  void commonPrefixDerivationIsDeterministicAcrossRuns() {
    // Same input, run twice: the derived (prefix-stripped) names must match exactly, since the
    // scheme has no randomness or hash-map-ordering dependence. A third prefixed role clears the
    // MIN_PREFIX_ROLES floor alongside the >50% share bar.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-rparobot", "CRUD", "CUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-systemupdate", "CRUD", "CU"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-caseadmin", "CRUD", "R"))
        .build();

    CaseTypeModel first = linker.link(ir, options("Minimal"), new GapCollector());
    CaseTypeModel second = linker.link(ir, options("Minimal"), new GapCollector());

    List<String> firstNames = first.getAccessClasses().stream()
        .map(AccessClassModel::getClassName).sorted().toList();
    List<String> secondNames = second.getAccessClasses().stream()
        .map(AccessClassModel::getClassName).sorted().toList();
    assertThat(firstNames).isEqualTo(secondNames);
    assertThat(first.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder("RparobotCudAccess", "SystemupdateCuAccess", "CaseadminRAccess");
  }

  @Test
  void uniformCrudAcrossMultiRoleGroupWritesCrudTokenOnce() {
    GapCollector gaps = new GapCollector();
    // Two groups qualify for mining (>=3 fields, >=2 atoms each): {caseworker=CRU, citizen=CRU}
    // (uniform CRUD, 3 fields) and {solicitor=R, expert=R} (4 fields, so it wins DefaultAccess).
    // The first group's content-derived name should then write its shared CRUD token once rather
    // than repeating it per role.
    var builder = minimal("Minimal");
    for (String id : new String[] {"a", "b", "c"}) {
      builder.row(SheetName.CASE_FIELD,
          cols("CaseTypeID", "Minimal", "ID", id, "Label", id, "FieldType", "Text"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", id, "UserRole", "caseworker",
                  "CRUD", "CRU"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", id, "UserRole", "citizen",
                  "CRUD", "CRU"));
    }
    for (String id : new String[] {"d", "e", "f", "g"}) {
      builder.row(SheetName.CASE_FIELD,
          cols("CaseTypeID", "Minimal", "ID", id, "Label", id, "FieldType", "Text"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", id, "UserRole", "solicitor",
                  "CRUD", "R"))
          .row(SheetName.AUTHORISATION_CASE_FIELD,
              cols("CaseTypeID", "Minimal", "CaseFieldID", id, "UserRole", "expert",
                  "CRUD", "R"));
    }
    CaseTypeModel model = linker.link(builder.build(), options("Minimal"), gaps);

    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .contains("CaseworkerCitizenCruAccess", "DefaultAccess");
  }

  @Test
  void derivesGroupWhoseRootIsPlacedInANonComplexContext() {
    GapCollector gaps = new GapCollector();
    // sscs's updateOtherPartyData/appeal: the root field is placed READONLY on the event yet still
    // carries per-member CaseEventToComplexTypes overrides. The old gate refused to derive unless the
    // placement was DisplayContext=COMPLEX, because .complex(getter) both registers the root field
    // AND opens the member scope — deriving would have rewritten the READONLY row to COMPLEX. The
    // scope is now opened by a NON-REGISTERING opener (.complexScope), so the placement's context is
    // irrelevant and the group derives.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "appeal", "Label", "Appeal",
                "FieldType", "Appeal"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Appeal", "ListElementCode", "benefitType", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "appeal", "DisplayContext", "READONLY", "PageID", "1",
                "PageFieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Appeal", "CaseEventID", "createCase", "CaseFieldID", "appeal",
                "ListElementCode", "benefitType", "DisplayContext", "MANDATORY",
                "FieldDisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCaseappeal");
    assertThat(group).as("a non-COMPLEX placement no longer blocks derivation").isNotNull();
    assertThat(group.getMembers()).singleElement()
        .satisfies(m -> assertThat(m.getLeafGetter()).isEqualTo("getBenefitType"));
    // The placement itself is untouched: the emitter puts the scope in a separate statement, so the
    // page field keeps the context the input asked for.
    assertThat(model.getEvents().get(0).getPages().get(0).getFields())
        .singleElement()
        .extracting(PageModel.PageField::getDisplayContext).isEqualTo("READONLY");
  }

  @Test
  void derivesGroupWhoseRootNoPagePlacesAtAll() {
    GapCollector gaps = new GapCollector();
    // sscs's dwpUploadResponse/otherParties: the event carries CaseEventToComplexTypes member rows for
    // a field that has NO CaseEventToFields row at all — no wizard page places it. The old gate needed
    // a COMPLEX placement to hang the scope off; a non-registering opener needs only the event's
    // the fields builder, which exists as long as the event places SOME page. The emitter emits these as
    // orphan scopes after the page fields.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "summary", "Label", "Summary", "FieldType", "Text"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "otherParties", "Label", "Other parties",
                "FieldType", "Collection", "FieldTypeParameter", "Party"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Party", "ListElementCode", "partyName", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "summary", "DisplayContext", "OPTIONAL", "PageID", "1",
                "PageFieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Party", "CaseEventID", "createCase", "CaseFieldID", "otherParties",
                "ListElementCode", "partyName", "DisplayContext", "OPTIONAL",
                "FieldDisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCaseotherParties");
    assertThat(group).as("an unplaced root still derives — the scope registers no field").isNotNull();
    assertThat(group.getRootElementType()).as("collection root → element-typed scope").isNotNull();
    assertThat(group.getMembers()).singleElement()
        .satisfies(m -> assertThat(m.getLeafGetter()).isEqualTo("getPartyName"));
    // Deriving must not invent a placement: the page still places only the field the input placed.
    assertThat(model.getEvents().get(0).getPages().get(0).getFields())
        .extracting(PageModel.PageField::getCaseFieldId).containsExactly("summary");
  }

  @Test
  void derivesMemberPlacedAsComplexInItsOwnRight() {
    GapCollector gaps = new GapCollector();
    // sscs's confirmPoAttendance/presentingOfficersDetails: an INTERMEDIATE member (contact) carries a
    // DisplayContext=COMPLEX row of its own alongside the dotted contact.phone rows beneath it. The
    // generator always emitted the member's context verbatim, so COMPLEX was never the obstacle —
    // what was missing was an SDK placement setting Complex context WITHOUT opening a nested scope.
    // That is .complexMember(getter), so this member now derives instead of falling back.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "poDetails", "Label", "PO details",
                "FieldType", "PoDetails"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "PoDetails", "ListElementCode", "contact", "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "phone", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_FIELDS,
            cols("CaseTypeID", "Minimal", "CaseEventID", "createCase",
                "CaseFieldID", "poDetails", "DisplayContext", "COMPLEX", "PageID", "1",
                "PageFieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "PoDetails", "CaseEventID", "createCase", "CaseFieldID", "poDetails",
                "ListElementCode", "contact", "DisplayContext", "COMPLEX",
                "FieldDisplayOrder", 1))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "createCase", "CaseFieldID", "poDetails",
                "ListElementCode", "contact.phone", "DisplayContext", "OPTIONAL",
                "FieldDisplayOrder", 2))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("createCasepoDetails");
    assertThat(group).isNotNull();
    assertThat(group.getMembers()).hasSize(2);
    var contact = group.getMembers().stream()
        .filter(m -> m.getHops().isEmpty()).findFirst().orElseThrow();
    assertThat(contact.getLeafGetter()).isEqualTo("getContact");
    assertThat(contact.getContextMethod())
        .as("a COMPLEX member row places with Complex context but opens no nested scope")
        .isEqualTo("complexMember");
    // Nothing falls back: the whole group is code.
    assertThat(model.getPassthroughSheets())
        .noneMatch(p -> p.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/poDetails.json"));
  }

  @Test
  void derivesGroupOnAnEventWithNoPagesAtAll() {
    GapCollector gaps = new GapCollector();
    // probate's boFindMatchedCaseGrantRegistrarEscalation/caseMatches: the event has NO
    // CaseEventToFields rows at all, yet carries member overrides. EventBuilder.fields() hands back
    // the event's collection builder without registering anything, so the emitter can open a bare
    // .fields() and hang the non-registering scope off it — a page-less event is not a refusal.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "silentEvent", "Name", "Silent",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "silentEvent", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    var group = model.getEventComplexTypeGroups().get("silentEventcontact");
    assertThat(group).as("a page-less event still derives").isNotNull();
    assertThat(group.getMembers()).singleElement()
        .satisfies(m -> assertThat(m.getLeafGetter()).isEqualTo("getName"));
    assertThat(model.getEvents().get(0).getPages())
        .as("deriving must not invent a page").isNullOrEmpty();
    assertThat(model.getPassthroughSheets())
        .noneMatch(p -> p.getRelativePath()
            .equals("CaseEventToComplexTypes/silentEvent/contact.json"));
  }

  @Test
  void groupNamingAnUndeclaredEventFallsBackWithNoEventReason() {
    GapCollector gaps = new GapCollector();
    // The last placement-shaped refusal: a member row naming an event no CaseEvent row declares. There
    // is no generated .event(...) block at all, so there is nothing to open a scope on and the rows
    // stay verbatim.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "contact", "Label", "Contact",
                "FieldType", "Contact"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Contact", "ListElementCode", "name", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Contact", "CaseEventID", "ghostEvent", "CaseFieldID", "contact",
                "ListElementCode", "name", "DisplayContext", "MANDATORY", "FieldDisplayOrder", 1))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getEventComplexTypeGroups()).doesNotContainKey("ghostEventcontact");
    assertThat(model.getPassthroughSheets())
        .anyMatch(p -> p.getRelativePath()
            .equals("CaseEventToComplexTypes/ghostEvent/contact.json"));
    assertThat(gaps.getEntries())
        .filteredOn(g -> "EventToComplexTypes".equals(g.getSheet()))
        .singleElement()
        .satisfies(g -> assertThat(g.getDetail())
            .contains("no CaseEvent row declares this event, so there is no event to place a scope on"));
  }

  /**
   * A minimal retrofit graph that answers only {@code rootPlacement}, so a test can pin how the
   * linker roots a group's scope without also standing up a parsed model source tree. Every other
   * query returns "no binding", which puts the member walk on the generated-complex-type path.
   */
  private static ConversionOptions retrofitOptionsWithPlacement(
      RetrofitModelTypeGraph.RootPlacement placement) {
    return ConversionOptions.builder()
        .caseTypeId("Minimal")
        .modelPackage("uk.gov.hmcts.test.model")
        .configPackage("uk.gov.hmcts.test.config")
        .retrofit(true)
        .retrofitModelTypeGraph(new RetrofitModelTypeGraph() {
          @Override
          public Optional<Handle> rootHandle(String caseFieldId) {
            return Optional.empty();
          }

          @Override
          public boolean rootIsCollection(String caseFieldId) {
            return false;
          }

          @Override
          public Optional<MemberResolution> member(Handle owner, String segment) {
            return Optional.empty();
          }

          @Override
          public Optional<Handle> complexTypeHandle(String complexTypeId) {
            return Optional.empty();
          }

          @Override
          public Optional<RootPlacement> rootPlacement(String caseFieldId) {
            return Optional.of(placement);
          }
        })
        .build();
  }

  private static DefinitionIr irWithOneMemberOverride() {
    return IrBuilder.builder()
        .row(SheetName.JURISDICTION, cols("ID", "TEST", "Name", "Test Jurisdiction"))
        .row(SheetName.CASE_TYPE,
            cols("ID", "Minimal", "Name", "Case", "JurisdictionID", "TEST"))
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "applicant1DQHearing", "Label", "Hearing",
                "FieldType", "Hearing"))
        .row(SheetName.COMPLEX_TYPES,
            cols("ID", "Hearing", "ListElementCode", "hearingLength", "FieldType", "Text"))
        .row(SheetName.CASE_EVENT,
            cols("CaseTypeID", "Minimal", "ID", "createCase", "Name", "Create",
                "PostConditionState", "Open"))
        .row(SheetName.CASE_EVENT_TO_COMPLEX_TYPES,
            cols("ID", "Hearing", "CaseEventID", "createCase", "CaseFieldID", "applicant1DQHearing",
                "ListElementCode", "hearingLength", "DisplayContext", "MANDATORY",
                "FieldDisplayOrder", 1))
        .build();
  }

  @Test
  void rootsAGroupThroughTheUnwrappedHopsTheModelDeclares() {
    GapCollector gaps = new GapCollector();
    // Civil's applicant1DQHearing: declared on model.dq.Applicant1DQ, reached through CaseData's
    // @JsonUnwrapped applicant1DQ. The group must carry the hop so the emitter descends the holder —
    // CaseData::getApplicant1DQHearing does not compile.
    ConversionOptions options = retrofitOptionsWithPlacement(
        RetrofitModelTypeGraph.RootPlacement.of("getApplicant1DQHearing",
            List.of(new RetrofitModelTypeGraph.PlacementHop(
                "getApplicant1DQ", "uk.gov.hmcts.test.model.dq.Applicant1DQ"))));

    CaseTypeModel model = linker.link(irWithOneMemberOverride(), options, gaps);

    var group = model.getEventComplexTypeGroups().get("createCaseapplicant1DQHearing");
    assertThat(group).as("a hop-rooted group still derives").isNotNull();
    assertThat(group.getRootGetter()).isEqualTo("getApplicant1DQHearing");
    assertThat(group.getRootHops()).singleElement().satisfies(hop -> {
      assertThat(hop.getGetter()).isEqualTo("getApplicant1DQ");
      assertThat(hop.getTargetType().getModelFqn())
          .isEqualTo("uk.gov.hmcts.test.model.dq.Applicant1DQ");
    });
    assertThat(model.getPassthroughSheets())
        .noneMatch(p -> p.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/applicant1DQHearing.json"));
  }

  @Test
  void refusesTheWholeGroupWhenTheModelExposesNoCompilableGetterChain() {
    GapCollector gaps = new GapCollector();
    // The holder's getter is suppressed, so no method reference can open the scope. The group must fall
    // back verbatim — the same refusal the rebinder applies to the PAGE placement of that field, so the
    // two placements of one field cannot disagree.
    ConversionOptions options =
        retrofitOptionsWithPlacement(RetrofitModelTypeGraph.RootPlacement.unreachable());

    CaseTypeModel model = linker.link(irWithOneMemberOverride(), options, gaps);

    assertThat(model.getEventComplexTypeGroups())
        .doesNotContainKey("createCaseapplicant1DQHearing");
    assertThat(model.getPassthroughSheets())
        .anyMatch(p -> p.getRelativePath()
            .equals("CaseEventToComplexTypes/createCase/applicant1DQHearing.json"));
    assertThat(gaps.getEntries())
        .filteredOn(g -> "EventToComplexTypes".equals(g.getSheet()))
        .anySatisfy(g -> assertThat(g.getDetail())
            .contains("the model exposes no compilable getter chain to this field"));
  }
}
