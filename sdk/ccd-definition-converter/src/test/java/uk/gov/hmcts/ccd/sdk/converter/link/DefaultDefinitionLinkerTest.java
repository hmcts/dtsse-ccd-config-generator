package uk.gov.hmcts.ccd.sdk.converter.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.converter.link.IrBuilder.cols;

import java.util.List;
import java.util.Map;
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
import uk.gov.hmcts.ccd.sdk.converter.model.RoleModel;
import uk.gov.hmcts.ccd.sdk.converter.model.StateModel;
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
        // (unreferenced complex types are passed through rather than emitted as classes).
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
    // directive): the derived atom names should drop it and keep only each role's remainder.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-rparobot", "CRUD", "CUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-systemupdate", "CRUD", "CU"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    // Both maintainer-example shapes resolve to short, prefix-free names: no "CaseworkerProbate"
    // token survives in either atom.
    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder("RparobotCudAccess", "SystemupdateCuAccess");
  }

  @Test
  void roleThatIsExactlyTheCommonPrefixKeepsItsLastToken() {
    GapCollector gaps = new GapCollector();
    // "caseworker-probate" IS the shared prefix "caseworker-probate" exactly (no remainder), so
    // per the documented rule it keeps its last hyphen token ("probate") rather than collapsing
    // to an empty name.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate", "CRUD", "R"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-caseadmin", "CRUD", "CRU"))
        .build();

    CaseTypeModel model = linker.link(ir, options("Minimal"), gaps);

    assertThat(model.getAccessClasses())
        .extracting(AccessClassModel::getClassName)
        .containsExactlyInAnyOrder("ProbateRAccess", "CaseadminCruAccess");
  }

  @Test
  void mixedPrefixCaseTypeStripsNothingBelowTheShareBar() {
    GapCollector gaps = new GapCollector();
    // Only 1 of 3 distinct roles shares "caseworker-probate-"; that is below the 80% bar, so no
    // prefix is common and every role keeps its full token form.
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
  void commonPrefixDerivationIsDeterministicAcrossRuns() {
    // Same input, run twice: the derived (prefix-stripped) names must match exactly, since the
    // scheme has no randomness or hash-map-ordering dependence.
    DefinitionIr ir = minimal("Minimal")
        .row(SheetName.CASE_FIELD,
            cols("CaseTypeID", "Minimal", "ID", "a", "Label", "A", "FieldType", "Text"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-rparobot", "CRUD", "CUD"))
        .row(SheetName.AUTHORISATION_CASE_FIELD,
            cols("CaseTypeID", "Minimal", "CaseFieldID", "a", "UserRole",
                "caseworker-probate-systemupdate", "CRUD", "CU"))
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
        .containsExactlyInAnyOrder("RparobotCudAccess", "SystemupdateCuAccess");
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
}
