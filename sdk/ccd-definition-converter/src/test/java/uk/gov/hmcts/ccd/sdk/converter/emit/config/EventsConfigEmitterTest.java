package uk.gov.hmcts.ccd.sdk.converter.emit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.api.ConversionOptions;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ClusteredFieldRef;
import uk.gov.hmcts.ccd.sdk.converter.model.EventComplexTypeGroup;
import uk.gov.hmcts.ccd.sdk.converter.model.EventModel;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.PageModel;
import uk.gov.hmcts.ccd.sdk.converter.model.RoleModel;
import uk.gov.hmcts.ccd.sdk.converter.model.gap.GapCollector;

/**
 * Tests for {@link EventsConfigEmitter}.
 */
class EventsConfigEmitterTest {

  private static EmitContext contextWith(int eventsPerConfig) {
    ConversionOptions opts = ConversionOptions.builder()
        .modelPackage(EnvironmentFlagsEmitterTest.MODEL_PKG)
        .configPackage(EnvironmentFlagsEmitterTest.CONFIG_PKG)
        .eventsPerConfig(eventsPerConfig)
        .build();
    return EmitContext.builder()
        .options(opts)
        .gaps(new GapCollector())
        .build();
  }

  private static EventModel simpleEvent(String id) {
    return EventModel.builder()
        .id(id)
        .javaName(id)
        .name("Event " + id)
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of("caseworker-test", "CRUD"))
        .pages(List.of())
        .build();
  }

  private static CaseTypeModel modelWithEvents(List<EventModel> events) {
    return modelWithEvents(events, List.of());
  }

  private static CaseTypeModel modelWithEvents(
      List<EventModel> events, List<FieldModel> caseFields) {
    return CaseTypeModel.builder()
        .caseTypeId("Minimal")
        .caseTypeName("Minimal Case")
        .caseTypeDescription("Test")
        .jurisdictionId("TEST")
        .jurisdictionName("Test Jurisdiction")
        .jurisdictionDescription("Fixture")
        .states(List.of())
        .roles(List.of(RoleModel.builder()
            .id("caseworker-test")
            .javaConstant("CASEWORKER_TEST")
            .caseTypePermissions("")
            .caseRole(false)
            .build()))
        .caseFields(caseFields)
        .complexTypes(List.of())
        .fixedLists(List.of())
        .events(events)
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of())
        .workBasketResultFields(List.of())
        .searchCasesResultFields(List.of())
        .stateAuthorisations(List.of())
        .accessClasses(List.of())
        .searchCriteria(List.of())
        .searchParties(List.of())
        .challengeQuestions(List.of())
        .roleToAccessProfiles(List.of())
        .categories(List.of())
        .passthroughSheets(List.of())
        .build();
  }

  @Test
  void emptyEventsProducesNoFiles() {
    List<JavaFile> files = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of()), contextWith(40));
    assertThat(files).isEmpty();
  }

  /** The source of the emitted class with the given simple name. */
  private static String classNamed(List<JavaFile> files, String simpleName) {
    return files.stream()
        .filter(f -> f.typeSpec().name().equals(simpleName))
        .map(JavaFile::toString)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no emitted class named " + simpleName));
  }

  /** The concatenated source of every emitted file (event class + its page classes). */
  private static String allSrc(List<JavaFile> files) {
    return files.stream().map(JavaFile::toString)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  @Test
  void oneClassPerEventNamedFromEventId() {
    // Finding #1: one CCDConfig class per event, PascalCase-named from the event ID, replacing the
    // numbered EventsConfigNN grab-bags.
    List<JavaFile> files = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(
            simpleEvent("createCase"), simpleEvent("closeCase"))), contextWith(40));
    List<String> names = files.stream().map(f -> f.typeSpec().name()).toList();
    assertThat(names).containsExactlyInAnyOrder("CreateCase", "CloseCase");
    assertThat(names).noneMatch(n -> n.startsWith("MinimalEventsConfig"));
  }

  @Test
  void eventClassIsInEventPackage() {
    String src = classNamed(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))), contextWith(40)), "CreateCase");
    assertThat(src).contains("package " + EnvironmentFlagsEmitterTest.CONFIG_PKG + ".event");
  }

  @Test
  void generatedClassImplementsCcdConfig() {
    String src = classNamed(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))), contextWith(40)), "CreateCase");
    assertThat(src).contains("implements CCDConfig");
  }

  @Test
  void eventIdDeclaredAsConstantAndReferenced() {
    // nfdiv idiom: the event class declares `public static final String CREATE_CASE = "createCase";`
    // and configure() references the constant rather than a raw literal.
    String src = classNamed(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))), contextWith(40)), "CreateCase");
    assertThat(src).contains("String CREATE_CASE = \"createCase\"");
    assertThat(src).contains("builder.event(CREATE_CASE)");
  }

  @Test
  void eventGrantsAreEmitted() {
    String src = classNamed(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))), contextWith(40)), "CreateCase");
    assertThat(src).contains("UserRole.CASEWORKER_TEST");
  }

  @Test
  void everyEventEmitsExplicitGrants() {
    // The converter emits .explicitGrants() on every event so event grants never cascade onto the
    // fields the event places; a converted config reproduces the input's AuthorisationCaseField
    // grants exactly rather than the SDK's event-grant union.
    String src = classNamed(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))), contextWith(40)), "CreateCase");
    assertThat(src).contains(".explicitGrants()");
  }

  private static EventModel eventWithDescription(String id, String name, String description) {
    return EventModel.builder()
        .id(id)
        .javaName(id)
        .name(name)
        .description(description)
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of("caseworker-test", "CRUD"))
        .pages(List.of())
        .build();
  }

  @Test
  void blankDescriptionIsEmittedExplicitlyRatherThanDefaultingToName() {
    // A CaseEvent authored with a blank/empty Description (civil's
    // CHECK_AND_MARK_PAID_IN_FULL: Description=" ") must round-trip that blank value, not fall
    // back to EventBuilder.name()'s implicit description-defaults-to-name behaviour.
    EventModel event = eventWithDescription(
        "checkPaidInFull", "Check and mark claimant paid", " ");

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event)), contextWith(40))
        .get(0).toString();

    assertThat(src).contains(".description(\" \")");
  }

  @Test
  void descriptionEqualToNameIsNotEmittedRedundantly() {
    EventModel event = eventWithDescription("createCase", "Create case", "Create case");

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event)), contextWith(40))
        .get(0).toString();

    assertThat(src).doesNotContain(".description(");
  }

  @Test
  void nullDescriptionIsNotEmitted() {
    EventModel event = eventWithDescription("createCase", "Create case", null);

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event)), contextWith(40))
        .get(0).toString();

    assertThat(src).doesNotContain(".description(");
  }

  @Test
  void multiPageEventEmitsFieldsChainAndPageClasses() {
    // A two-page event terminates its header at .fields() and delegates each page to its own class
    // in <root>.event.page (finding #2).
    PageModel.PageField field1 = PageModel.PageField.builder()
        .caseFieldId("applicantName")
        .displayContext("MANDATORY")
        .build();
    PageModel.PageField field2 = PageModel.PageField.builder()
        .caseFieldId("applicantEmail")
        .displayContext("OPTIONAL")
        .build();
    PageModel page1 = PageModel.builder().pageId("1").fields(List.of(field1)).build();
    PageModel page2 = PageModel.builder().pageId("2").fields(List.of(field2)).build();
    EventModel event = EventModel.builder()
        .id("createCase")
        .javaName("createCase")
        .name("Create Case")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of())
        .pages(List.of(page1, page2))
        .build();
    FieldModel applicantName = FieldModel.builder()
        .id("applicantName").javaName("applicantName").fieldType("Text").build();
    FieldModel applicantEmail = FieldModel.builder()
        .id("applicantEmail").javaName("applicantEmail").fieldType("Text").build();

    List<JavaFile> files = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(applicantName, applicantEmail)),
            contextWith(40));

    String eventSrc = classNamed(files, "CreateCase");
    assertThat(eventSrc).contains(".fields()");
    assertThat(eventSrc).contains("CreateCasePage1.apply(fields)");
    assertThat(eventSrc).contains("CreateCasePage2.apply(fields)");
    // The placements live in the page classes, not the event class.
    assertThat(eventSrc).doesNotContain(".mandatory(");
    String pageSrc = classNamed(files, "CreateCasePage1");
    assertThat(pageSrc).contains("package " + EnvironmentFlagsEmitterTest.CONFIG_PKG
        + ".event.page");
    assertThat(pageSrc).contains(".page(\"1\")");
    assertThat(pageSrc).contains(".mandatory(");
  }

  @Test
  void singlePageEventInlinesPageWithNoPageClass() {
    // A one-page event inlines its page header and field chain into configure() — a page class
    // would be pure indirection — so no page class is emitted and the event is the only file.
    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("applicantName")
        .displayContext("MANDATORY")
        .build();
    PageModel page = PageModel.builder()
        .pageId("1")
        .fields(List.of(field))
        .build();
    EventModel event = EventModel.builder()
        .id("createCase")
        .javaName("createCase")
        .name("Create Case")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of())
        .pages(List.of(page))
        .build();
    FieldModel applicantName = FieldModel.builder()
        .id("applicantName")
        .javaName("applicantName")
        .fieldType("Text")
        .build();

    List<JavaFile> files = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(applicantName)), contextWith(40));

    // Only the event class is emitted — no page class exists to delegate to.
    assertThat(files.stream().map(f -> f.typeSpec().name()).toList())
        .containsExactly("CreateCase");
    String eventSrc = classNamed(files, "CreateCase");
    assertThat(eventSrc).contains("var fields = builder.event(CREATE_CASE)");
    assertThat(eventSrc).contains(".fields()");
    // The page header and placement are inlined directly, not delegated.
    assertThat(eventSrc).contains("fields.page(\"1\")");
    assertThat(eventSrc).contains("fields.mandatory(CaseData::getApplicantName)");
    assertThat(eventSrc).doesNotContain(".apply(fields)");
  }

  @Test
  void singlePageEventInlinesPageLabelAndShowConditionWithMidEventCallback() {
    // A single-page event whose one page carries a page label and a show condition must inline both
    // (fields.pageLabel / fields.showCondition) alongside the field chain. A mid-event callback URL
    // is never SDK-wired — it is carried through verbatim on the CaseEventToFields column
    // passthrough — so the inlined page still emits the bare fields.page(id) (no callback arg),
    // exactly as the page class would have.
    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("applicantName")
        .displayContext("MANDATORY")
        .build();
    PageModel page = PageModel.builder()
        .pageId("selectHearing")
        .label("Select a hearing")
        .showCondition("applicantName=\"Yes\"")
        .fields(List.of(field))
        .build();
    EventModel event = EventModel.builder()
        .id("selectHearing")
        .javaName("selectHearing")
        .name("Select hearing")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of())
        .pages(List.of(page))
        .build();
    FieldModel applicantName = FieldModel.builder()
        .id("applicantName")
        .javaName("applicantName")
        .fieldType("Text")
        .build();

    List<JavaFile> files = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(applicantName)), contextWith(40));

    assertThat(files.stream().map(f -> f.typeSpec().name()).toList())
        .containsExactly("SelectHearing");
    String eventSrc = classNamed(files, "SelectHearing");
    assertThat(eventSrc).contains("fields.page(\"selectHearing\")");
    assertThat(eventSrc).contains("fields.pageLabel(\"Select a hearing\")");
    assertThat(eventSrc).contains("fields.showCondition(\"applicantName=\\\"Yes\\\"\")");
    assertThat(eventSrc).contains("fields.mandatory(CaseData::getApplicantName)");
    // No mid-event callback overload — page(id) only.
    assertThat(eventSrc).doesNotContain("fields.page(\"selectHearing\",");
    assertThat(eventSrc).doesNotContain(".apply(fields)");
  }

  @Test
  void skipsUnplaceableFieldAndRecordsGapWithoutEmittingABrokenGetter() {
    // Bug4 (retrofit): a field reached through a @JsonUnwrapped parent whose getter the model
    // suppresses (@Getter(AccessLevel.NONE), no correctly-named accessor) must NOT be placed via a
    // typed getter — the SDK has no public string-id overload for event fields — so the emitter skips
    // it and records a PASSTHROUGH_COLUMN gap rather than emitting a broken CaseData::getParent ref.
    PageModel.PageField placeable = PageModel.PageField.builder()
        .caseFieldId("applicantName").displayContext("MANDATORY").build();
    PageModel.PageField unplaceable = PageModel.PageField.builder()
        .caseFieldId("writeFinalDecisionPreviewDocument").displayContext("MANDATORY").build();
    PageModel page = PageModel.builder()
        .pageId("1").fields(List.of(placeable, unplaceable)).build();
    EventModel event = EventModel.builder()
        .id("issueDecision").javaName("issueDecision").name("Issue decision")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page)).build();
    FieldModel applicantName = FieldModel.builder()
        .id("applicantName").javaName("applicantName").fieldType("Text").build();
    FieldModel finalDecision = FieldModel.builder()
        .id("writeFinalDecisionPreviewDocument").javaName("writeFinalDecisionPreviewDocument")
        .fieldType("Document").build();

    CaseTypeModel model = modelWithEvents(List.of(event), List.of(applicantName, finalDecision))
        .toBuilder()
        .unplaceableFieldIds(java.util.Set.of("writeFinalDecisionPreviewDocument"))
        .build();
    GapCollector gaps = new GapCollector();
    EmitContext context = EmitContext.builder()
        .options(ConversionOptions.builder()
            .modelPackage(EnvironmentFlagsEmitterTest.MODEL_PKG)
            .configPackage(EnvironmentFlagsEmitterTest.CONFIG_PKG)
            .eventsPerConfig(40).build())
        .gaps(gaps)
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, context));

    // The placeable field is still emitted; the unplaceable one is not referenced by any getter.
    assertThat(src).contains("getApplicantName");
    assertThat(src).doesNotContain("getWriteFinalDecisionPreviewDocument");
    // A gap records the skipped placement so it is not a silent omission.
    assertThat(gaps.getEntries())
        .anySatisfy(g -> {
          assertThat(g.getRowKey()).isEqualTo("issueDecision/writeFinalDecisionPreviewDocument");
          assertThat(g.getAction())
              .isEqualTo(uk.gov.hmcts.ccd.sdk.converter.model.gap.GapAction.PASSTHROUGH_COLUMN);
          assertThat(g.getDetail()).contains("@Getter(AccessLevel.NONE)");
        });
  }

  @Test
  void negatedOverlayGuardEmitsFlagWithoutNegation() {
    EventModel event = EventModel.builder()
        .id("debugReset")
        .javaName("debugReset_nonprod")
        .name("Debug reset")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of())
        .pages(List.of())
        .overlayCondition(uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition.parse(
            "!CCD_DEF_ENV:prod"))
        .build();

    String src = classNamed(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event)), contextWith(40)), "DebugResetNonprod");

    assertThat(src).contains("if (EnvironmentFlags.flag(");
    assertThat(src).doesNotContain("if (!EnvironmentFlags.flag(");
  }

  @Test
  void nonNegatedOverlayGuardEmitsNegatedFlagCheck() {
    EventModel event = EventModel.builder()
        .id("archiveCase")
        .javaName("archiveCase_prod")
        .name("Archive case")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of())
        .pages(List.of())
        .overlayCondition(uk.gov.hmcts.ccd.sdk.converter.model.OverlayCondition.parse(
            "CCD_DEF_ENV:prod"))
        .build();

    String src = classNamed(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event)), contextWith(40)), "ArchiveCaseProd");

    assertThat(src).contains("if (!EnvironmentFlags.flag(");
  }

  @Test
  void pageFieldGetterIsDerivedFromJavaNameNotRawId() {
    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("case-notes-2")
        .displayContext("MANDATORY")
        .build();
    PageModel page = PageModel.builder()
        .pageId("1")
        .fields(List.of(field))
        .build();
    EventModel event = EventModel.builder()
        .id("addNotes")
        .javaName("addNotes")
        .name("Add notes")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of())
        .pages(List.of(page))
        .build();
    FieldModel caseNotes2 = FieldModel.builder()
        .id("case-notes-2")
        .javaName("case_notes_2")
        .fieldType("Text")
        .build();

    String src = allSrc(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(caseNotes2)), contextWith(40)));

    assertThat(src).contains("CaseData::getCase_notes_2");
    assertThat(src).doesNotContain("CaseData::getCase-notes-2");
  }

  @Test
  void labelPageFieldEmitsReadonlyMemberReference() {
    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("infoLabel")
        .displayContext("READONLY")
        .build();
    PageModel page = PageModel.builder()
        .pageId("1")
        .fields(List.of(field))
        .build();
    EventModel event = EventModel.builder()
        .id("addNotes")
        .javaName("addNotes")
        .name("Add notes")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of())
        .pages(List.of(page))
        .build();
    FieldModel infoLabel = FieldModel.builder()
        .id("infoLabel")
        .javaName("infoLabel")
        .fieldType("Label")
        .label("Some information")
        .build();

    String src = allSrc(new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(infoLabel)), contextWith(40)));

    // Label fields are now real CaseData members referenced as readonly, not inline .label(...).
    assertThat(src).contains("readonly(CaseData::getInfoLabel)");
    assertThat(src).doesNotContain(".label(\"infoLabel\"");
  }

  @Test
  void grantOnCaseRoleEmitsJavaConstantFromRoleModel() {
    EventModel event = EventModel.builder()
        .id("addNotes")
        .javaName("addNotes")
        .name("Add notes")
        .preStates(List.of())
        .postState("Open")
        .grants(Map.of("[CREATOR]", "R"))
        .pages(List.of())
        .build();
    CaseTypeModel model = CaseTypeModel.builder()
        .caseTypeId("Minimal")
        .caseTypeName("Minimal Case")
        .caseTypeDescription("Test")
        .jurisdictionId("TEST")
        .jurisdictionName("Test Jurisdiction")
        .jurisdictionDescription("Fixture")
        .states(List.of())
        .roles(List.of(RoleModel.builder()
            .id("[CREATOR]")
            .javaConstant("CREATOR")
            .caseTypePermissions("")
            .caseRole(true)
            .build()))
        .caseFields(List.of())
        .complexTypes(List.of())
        .fixedLists(List.of())
        .events(List.of(event))
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of())
        .workBasketResultFields(List.of())
        .searchCasesResultFields(List.of())
        .stateAuthorisations(List.of())
        .accessClasses(List.of())
        .searchCriteria(List.of())
        .searchParties(List.of())
        .challengeQuestions(List.of())
        .roleToAccessProfiles(List.of())
        .categories(List.of())
        .passthroughSheets(List.of())
        .build();

    String src = classNamed(new EventsConfigEmitter().emit(model, contextWith(40)), "AddNotes");

    assertThat(src).contains("UserRole.CREATOR");
  }

  @Test
  void collectionRootedGroupOpensElementTypedScopeAndEmitsHintTriState() {
    // A Collection-rooted CaseEventToComplexTypes group: the collection field's own COMPLEX row is
    // registered by the one-arg .complex(getter).done(), and the element members are placed in a
    // SEPARATE statement opening the two-arg element-typed .complex(getter, Element.class) scope. A
    // nested collection hop uses the same two-arg form; the hint tri-state emits .hintText/.noHintText.
    EventComplexTypeGroup.TypeRef party =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Party").build();
    EventComplexTypeGroup.TypeRef child =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Child").build();
    EventComplexTypeGroup group = EventComplexTypeGroup.builder()
        .eventId("createCase")
        .caseFieldId("parties")
        .rootGetter("getParties")
        .rootElementType(party)
        // A direct element member overriding its HintText.
        .members(List.of(
            EventComplexTypeGroup.Member.builder()
                .hops(List.of())
                .leafType(party)
                .leafGetter("getPartyName")
                .contextMethod("mandatory")
                .hintOverridden(true)
                .hintText("An overriding hint")
                .build(),
            // An element member suppressing a would-be cascade.
            EventComplexTypeGroup.Member.builder()
                .hops(List.of())
                .leafType(party)
                .leafGetter("getReference")
                .contextMethod("readonly")
                .hintOverridden(true)
                .hintText(null)
                .retainHiddenValue(true)
                .build(),
            // A nested collection hop (Collection<Child>) descended via the element-typed scope.
            EventComplexTypeGroup.Member.builder()
                .hops(List.of(EventComplexTypeGroup.Hop.builder()
                    .declaringType(party)
                    .getter("getChildren")
                    .elementType(child)
                    .build()))
                .leafType(child)
                .leafGetter("getChildName")
                .contextMethod("mandatory")
                .build()))
        .build();

    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("parties")
        .displayContext("COMPLEX")
        .build();
    PageModel page = PageModel.builder().pageId("1").fields(List.of(field)).build();
    EventModel event = EventModel.builder()
        .id("createCase").javaName("createCase").name("Create Case")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    FieldModel parties = FieldModel.builder()
        .id("parties").javaName("parties").fieldType("Collection").fieldTypeParameter("Party")
        .build();
    CaseTypeModel model = modelWithEvents(List.of(event), List.of(parties)).toBuilder()
        .eventComplexTypeGroups(Map.of("createCaseparties", group))
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    // The collection field's own COMPLEX row is registered by the bare one-arg scope.
    assertThat(src).contains("fields.complex(CaseData::getParties).done()");
    // The element members hang off a separate element-typed scope.
    assertThat(src).contains("fields.complex(CaseData::getParties, Party.class)");
    assertThat(src).contains(".mandatory(Party::getPartyName)");
    assertThat(src).contains(".hintText(\"An overriding hint\")");
    assertThat(src).contains(".readonly(Party::getReference)");
    assertThat(src).contains(".noHintText()");
    // RetainHiddenValue is a real importer-read column on this sheet, derived rather than grafted.
    assertThat(src).contains(".retainHiddenValue()");
    // The nested collection hop opens its own two-arg element-typed scope.
    assertThat(src).contains(".complex(Party::getChildren, Child.class)");
    assertThat(src).contains(".mandatory(Child::getChildName)");
  }

  @Test
  void groupMemberTypeIsImportedFromTheRetrofitOverridePackage() {
    // In retrofit mode a group's element/leaf type is often a class the team declares OUTSIDE the
    // model package (prl's WelshNeed/FurtherEvidence in models.complextypes for a models.dto.ccd
    // model package). Defaulting the reference to the model package imports a type that exists
    // nowhere, so every such reference fails to compile; the emitter must resolve it through the same
    // retrofitTypeFqnOverrides map the complex-type emitter uses.
    EventComplexTypeGroup.TypeRef party =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Party").build();
    EventComplexTypeGroup group = EventComplexTypeGroup.builder()
        .eventId("createCase")
        .caseFieldId("parties")
        .rootGetter("getParties")
        .rootElementType(party)
        .members(List.of(EventComplexTypeGroup.Member.builder()
            .hops(List.of())
            .leafType(party)
            .leafGetter("getPartyName")
            .contextMethod("mandatory")
            .build()))
        .build();

    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("parties")
        .displayContext("COMPLEX")
        .build();
    PageModel page = PageModel.builder().pageId("1").fields(List.of(field)).build();
    EventModel event = EventModel.builder()
        .id("createCase").javaName("createCase").name("Create Case")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    FieldModel parties = FieldModel.builder()
        .id("parties").javaName("parties").fieldType("Collection").fieldTypeParameter("Party")
        .build();
    CaseTypeModel model = modelWithEvents(List.of(event), List.of(parties)).toBuilder()
        .eventComplexTypeGroups(Map.of("createCaseparties", group))
        .build();

    ConversionOptions opts = ConversionOptions.builder()
        .modelPackage(EnvironmentFlagsEmitterTest.MODEL_PKG)
        .configPackage(EnvironmentFlagsEmitterTest.CONFIG_PKG)
        .eventsPerConfig(40)
        .retrofitTypeFqnOverrides(Map.of("Party", "uk.gov.hmcts.test.other.Party"))
        .build();
    EmitContext context = EmitContext.builder()
        .options(opts)
        .gaps(new GapCollector())
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, context));

    assertThat(src).contains("import uk.gov.hmcts.test.other.Party;");
    assertThat(src).doesNotContain("import " + EnvironmentFlagsEmitterTest.MODEL_PKG + ".Party;");
  }

  @Test
  void scalarRootedGroupOnANonComplexPlacementOpensAScopeWithoutChangingTheFieldRow() {
    // sscs updateOtherPartyData/appeal: the complex field is placed READONLY yet still carries
    // per-member CaseEventToComplexTypes overrides. Its own CaseEventToFields row must stay READONLY,
    // so the members hang off a SEPARATE non-registering .complexScope(getter) statement rather than a
    // .complex(getter) block, which would force the row to COMPLEX.
    EventComplexTypeGroup.TypeRef appeal =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Appeal").build();
    EventComplexTypeGroup group = EventComplexTypeGroup.builder()
        .eventId("createCase")
        .caseFieldId("appeal")
        .rootGetter("getAppeal")
        .members(List.of(EventComplexTypeGroup.Member.builder()
            .hops(List.of())
            .leafType(appeal)
            .leafGetter("getBenefitType")
            .contextMethod("mandatory")
            .build()))
        .build();

    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("appeal")
        .displayContext("READONLY")
        .build();
    PageModel page = PageModel.builder().pageId("1").fields(List.of(field)).build();
    EventModel event = EventModel.builder()
        .id("createCase").javaName("createCase").name("Create Case")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    FieldModel appealField = FieldModel.builder()
        .id("appeal").javaName("appeal").fieldType("Appeal")
        .build();
    CaseTypeModel model = modelWithEvents(List.of(event), List.of(appealField)).toBuilder()
        .eventComplexTypeGroups(Map.of("createCaseappeal", group))
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    // The placement keeps its own context — no .complex(getter) block anywhere.
    assertThat(src).contains("fields.readonly(CaseData::getAppeal)");
    assertThat(src).doesNotContain("fields.complex(CaseData::getAppeal)");
    // The members hang off the non-registering scalar scope.
    assertThat(src).contains("fields.complexScope(CaseData::getAppeal)");
    assertThat(src).contains(".mandatory(Appeal::getBenefitType)");
  }

  @Test
  void aClusteredLeafPlacedComplexKeepsThatContext() {
    // sscs caseUpdated/jointPartyAddress: the field is reached through a prefix-less @JsonUnwrapped
    // holder, so it is placed inside the holder's member scope — and the input places it COMPLEX. The
    // top-level path spells that .complex(getter), which both registers the row AND opens a scope; a
    // clustered leaf is already inside a scope, so it needs the row alone. Collapsing COMPLEX to
    // .optional here wrote DisplayContext=OPTIONAL over every such row.
    PageModel.PageField complexLeaf = PageModel.PageField.builder()
        .caseFieldId("jointPartyAddress")
        .displayContext("COMPLEX")
        .showCondition("jointParty=\"Yes\"")
        .build();
    PageModel.PageField optionalLeaf = PageModel.PageField.builder()
        .caseFieldId("jointParty")
        .displayContext("OPTIONAL")
        .build();
    PageModel page = PageModel.builder()
        .pageId("1").fields(List.of(complexLeaf, optionalLeaf)).build();
    EventModel event = EventModel.builder()
        .id("caseUpdated").javaName("caseUpdated").name("Update case data")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    CaseTypeModel model = modelWithEvents(List.of(event), List.of(
        FieldModel.builder().id("jointPartyAddress").javaName("address").fieldType("Address")
            .build(),
        FieldModel.builder().id("jointParty").javaName("hasJointParty").fieldType("YesOrNo")
            .build()))
        .toBuilder()
        .clusteredFieldRefs(Map.of(
            "jointPartyAddress", ClusteredFieldRef.builder()
                .parentGetter("getJointParty").clusterType("JointParty")
                .memberGetter("getAddress").build(),
            "jointParty", ClusteredFieldRef.builder()
                .parentGetter("getJointParty").clusterType("JointParty")
                .memberGetter("getHasJointParty").build()))
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    // The COMPLEX leaf is placed as a member row without opening a nested scope on it, and its
    // per-field metadata still chains onto the placement.
    assertThat(src).contains(".complexMember(JointParty::getAddress)");
    assertThat(src).contains(".fieldShowCondition(\"jointParty=\\\"Yes\\\"\")");
    // A sibling in another context is unaffected, and both stay inside the one holder scope.
    assertThat(src).contains(".optional(JointParty::getHasJointParty)");
    assertThat(src).containsOnlyOnce("fields.complex(CaseData::getJointParty)");
    // Nothing opens a scope on the leaf itself — that would register a second, nested row.
    assertThat(src).doesNotContain(".complex(JointParty::getAddress)");
  }

  @Test
  void aClusteredComplexLeafWithoutASummaryFlagTakesTheNoSummaryVariant() {
    // The clustered branch selects the *NoSummary sibling by name whenever the input row carries
    // ShowSummaryChangeOption=N, since the SDK otherwise defaults the flag to Y. COMPLEX has to
    // compose the same way as every other context — probate ships 41 such rows and et 26 — so a
    // COMPLEX method name with no NoSummary sibling would emit a call that does not compile.
    PageModel.PageField leaf = PageModel.PageField.builder()
        .caseFieldId("jointPartyAddress")
        .displayContext("COMPLEX")
        .showSummary(false)
        .build();
    PageModel page = PageModel.builder().pageId("1").fields(List.of(leaf)).build();
    EventModel event = EventModel.builder()
        .id("caseUpdated").javaName("caseUpdated").name("Update case data")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    CaseTypeModel model = modelWithEvents(List.of(event), List.of(
        FieldModel.builder().id("jointPartyAddress").javaName("address").fieldType("Address")
            .build()))
        .toBuilder()
        .clusteredFieldRefs(Map.of(
            "jointPartyAddress", ClusteredFieldRef.builder()
                .parentGetter("getJointParty").clusterType("JointParty")
                .memberGetter("getAddress").build()))
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    assertThat(src).contains(".complexMemberNoSummary(JointParty::getAddress)");
  }

  @Test
  void hopRootedGroupDescendsTheUnwrappedHolderBeforeOpeningTheMemberScope() {
    // Civil DEFENDANT_RESPONSE/applicant1DQHearing: in retrofit mode the complex field is declared on a
    // @JsonUnwrapped holder's class, so CaseData has no getter for it. The scope must descend the holder
    // with a further NON-REGISTERING .complex(holderGetter) hop and invoke the field's getter on the
    // holder's type — CaseData::getApplicant1DQHearing does not compile. One .done() closes the member
    // scope and one more closes each hop back to the case-data class.
    EventComplexTypeGroup.TypeRef holder = EventComplexTypeGroup.TypeRef.builder()
        .modelFqn("uk.gov.hmcts.test.model.dq.Applicant1DQ").build();
    EventComplexTypeGroup.TypeRef hearing =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Hearing").build();
    EventComplexTypeGroup group = EventComplexTypeGroup.builder()
        .eventId("createCase")
        .caseFieldId("applicant1DQHearing")
        .rootGetter("getApplicant1DQHearing")
        .rootHops(List.of(EventComplexTypeGroup.RootHop.builder()
            .getter("getApplicant1DQ")
            .targetType(holder)
            .build()))
        .members(List.of(EventComplexTypeGroup.Member.builder()
            .hops(List.of())
            .leafType(hearing)
            .leafGetter("getHearingLength")
            .contextMethod("optional")
            .build()))
        .build();

    // The event places a different field, so the group is emitted as an orphan scope.
    PageModel.PageField placed = PageModel.PageField.builder()
        .caseFieldId("summary").displayContext("READONLY").build();
    PageModel page = PageModel.builder().pageId("1").fields(List.of(placed)).build();
    EventModel event = EventModel.builder()
        .id("createCase").javaName("createCase").name("Create Case")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    FieldModel summary = FieldModel.builder()
        .id("summary").javaName("summary").fieldType("Text").build();
    FieldModel hearingField = FieldModel.builder()
        .id("applicant1DQHearing").javaName("applicant1DQHearing").fieldType("Hearing").build();
    CaseTypeModel model = modelWithEvents(List.of(event), List.of(summary, hearingField)).toBuilder()
        .eventComplexTypeGroups(Map.of("createCaseapplicant1DQHearing", group))
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    assertThat(src).contains("fields.complex(CaseData::getApplicant1DQ)");
    assertThat(src).contains(".complexScope(Applicant1DQ::getApplicant1DQHearing)");
    assertThat(src).contains(".optional(Hearing::getHearingLength)");
    // Never rooted on the case-data class — that is exactly the reference that would not compile.
    assertThat(src).doesNotContain("CaseData::getApplicant1DQHearing");
    // The holder's type is imported from its own sub-package, not assumed to sit beside CaseData.
    assertThat(src).contains("import uk.gov.hmcts.test.model.dq.Applicant1DQ;");
    // Two .done() calls: one closing the member scope, one closing the hop.
    assertThat(src).contains(".optional(Hearing::getHearingLength).done().done()");
  }

  @Test
  void groupOnAFieldNoPagePlacesAtAllEmitsAnOrphanScope() {
    // sscs dwpUploadResponse/otherParties (52 of its 60 residual rows): the event carries member
    // overrides for a field none of its pages place. There is no placement to anchor the scope to, so
    // it is emitted standalone after every page has been applied. A non-registering scope adds no
    // CaseEventToFields row, so it cannot disturb the placements above it.
    EventComplexTypeGroup.TypeRef party =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Party").build();
    EventComplexTypeGroup group = EventComplexTypeGroup.builder()
        .eventId("createCase")
        .caseFieldId("otherParties")
        .rootGetter("getOtherParties")
        .rootElementType(party)
        .members(List.of(EventComplexTypeGroup.Member.builder()
            .hops(List.of())
            .leafType(party)
            .leafGetter("getPartyName")
            .contextMethod("optional")
            .build()))
        .build();

    // The page places a DIFFERENT field; otherParties is placed nowhere.
    PageModel.PageField placed = PageModel.PageField.builder()
        .caseFieldId("summary")
        .displayContext("READONLY")
        .build();
    PageModel page = PageModel.builder().pageId("1").fields(List.of(placed)).build();
    EventModel event = EventModel.builder()
        .id("createCase").javaName("createCase").name("Create Case")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    FieldModel summary = FieldModel.builder()
        .id("summary").javaName("summary").fieldType("Text").build();
    FieldModel otherParties = FieldModel.builder()
        .id("otherParties").javaName("otherParties").fieldType("Collection")
        .fieldTypeParameter("Party").build();
    CaseTypeModel model =
        modelWithEvents(List.of(event), List.of(summary, otherParties)).toBuilder()
            .eventComplexTypeGroups(Map.of("createCaseotherParties", group))
            .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    // No placement is invented for the orphan root — only the element-typed scope.
    assertThat(src).doesNotContain("fields.optional(CaseData::getOtherParties)");
    assertThat(src).doesNotContain("fields.complex(CaseData::getOtherParties).done()");
    assertThat(src).contains("fields.complex(CaseData::getOtherParties, Party.class)");
    assertThat(src).contains(".optional(Party::getPartyName)");
  }

  @Test
  void manyOrphanScopesAreSplitOutOfConfigureIntoScopesClasses() {
    // prl's editAndApproveAnOrder: 39 orphan groups, ~700 member lambdas, all previously inlined into
    // configure(), which the javac emitted as "code too large" (the 64 KB per-method cap, the same one
    // the page path already splits for). Each scope registers nothing, so hoisting the runs into
    // <Event>ScopesN.apply(fields) cannot change the generated definition.
    List<EventComplexTypeGroup> groups = new ArrayList<>();
    List<FieldModel> fields = new ArrayList<>();
    Map<String, EventComplexTypeGroup> byKey = new java.util.LinkedHashMap<>();
    EventComplexTypeGroup.TypeRef party =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Party").build();
    // 40 groups × 5 members = 200 lambdas, comfortably over the 120 per-helper budget.
    for (int i = 0; i < 40; i++) {
      String fieldId = "orphan" + i;
      List<EventComplexTypeGroup.Member> members = new ArrayList<>();
      for (int m = 0; m < 5; m++) {
        members.add(EventComplexTypeGroup.Member.builder()
            .hops(List.of())
            .leafType(party)
            .leafGetter("getMember" + m)
            .contextMethod("optional")
            .build());
      }
      groups.add(EventComplexTypeGroup.builder()
          .eventId("createCase")
          .caseFieldId(fieldId)
          .rootGetter("get" + Character.toUpperCase(fieldId.charAt(0)) + fieldId.substring(1))
          .rootElementType(party)
          .members(members)
          .build());
      fields.add(FieldModel.builder()
          .id(fieldId).javaName(fieldId).fieldType("Collection").fieldTypeParameter("Party")
          .build());
      byKey.put("createCase" + fieldId, groups.get(i));
    }

    PageModel page = PageModel.builder().pageId("1").fields(List.of(PageModel.PageField.builder()
        .caseFieldId("summary").displayContext("READONLY").build())).build();
    EventModel event = EventModel.builder()
        .id("createCase").javaName("createCase").name("Create Case")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    fields.add(FieldModel.builder().id("summary").javaName("summary").fieldType("Text").build());
    CaseTypeModel model = modelWithEvents(List.of(event), fields).toBuilder()
        .eventComplexTypeGroups(byKey)
        .build();

    List<JavaFile> files = new EventsConfigEmitter().emit(model, contextWith(40));
    List<String> names = files.stream().map(f -> f.typeSpec().name()).toList();
    assertThat(names).as("the runs are hoisted into numbered Scopes classes")
        .contains("CreateCaseScopes1", "CreateCaseScopes2");

    String eventSrc = files.stream().filter(f -> f.typeSpec().name().equals("CreateCase"))
        .map(JavaFile::toString).findFirst().orElseThrow();
    assertThat(eventSrc).as("configure() invokes them rather than inlining the lambdas")
        .contains("CreateCaseScopes1.apply(fields)");
    assertThat(eventSrc).doesNotContain("Party::getMember0");

    // Every group still reaches the builder exactly once, so the definition is unchanged.
    String allSrc = allSrc(files);
    for (int i = 0; i < 40; i++) {
      String opener = "fields.complex(CaseData::getOrphan" + i + ", Party.class)";
      assertThat(allSrc).as("group " + i + " must still be emitted").contains(opener);
    }
  }

  @Test
  void complexMemberContextEmitsAMemberRowWithoutOpeningANestedScope() {
    // sscs confirmPoAttendance/presentingOfficersDetails: an intermediate (contact) carries a
    // DisplayContext=COMPLEX row of its OWN alongside dotted rows for its leaves. .complexMember places
    // the member with Complex context and opens no scope, so the two compose on one intermediate.
    EventComplexTypeGroup.TypeRef details =
        EventComplexTypeGroup.TypeRef.builder().simpleName("PoDetails").build();
    EventComplexTypeGroup.TypeRef contact =
        EventComplexTypeGroup.TypeRef.builder().simpleName("Contact").build();
    EventComplexTypeGroup group = EventComplexTypeGroup.builder()
        .eventId("createCase")
        .caseFieldId("presentingOfficersDetails")
        .rootGetter("getPresentingOfficersDetails")
        .members(List.of(
            // The intermediate as a COMPLEX member row in its own right.
            EventComplexTypeGroup.Member.builder()
                .hops(List.of())
                .leafType(details)
                .leafGetter("getContact")
                .contextMethod("complexMember")
                .build(),
            // ...and descended into for its leaf, which emits the dotted ListElementCode.
            EventComplexTypeGroup.Member.builder()
                .hops(List.of(EventComplexTypeGroup.Hop.builder()
                    .declaringType(details)
                    .getter("getContact")
                    .build()))
                .leafType(contact)
                .leafGetter("getPhone")
                .contextMethod("optional")
                .build()))
        .build();

    PageModel.PageField field = PageModel.PageField.builder()
        .caseFieldId("presentingOfficersDetails")
        .displayContext("COMPLEX")
        .build();
    PageModel page = PageModel.builder().pageId("1").fields(List.of(field)).build();
    EventModel event = EventModel.builder()
        .id("createCase").javaName("createCase").name("Create Case")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of(page))
        .build();
    FieldModel po = FieldModel.builder()
        .id("presentingOfficersDetails").javaName("presentingOfficersDetails")
        .fieldType("PoDetails").build();
    CaseTypeModel model = modelWithEvents(List.of(event), List.of(po)).toBuilder()
        .eventComplexTypeGroups(Map.of("createCasepresentingOfficersDetails", group))
        .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    assertThat(src).contains(".complexMember(PoDetails::getContact)");
    assertThat(src).contains(".complex(PoDetails::getContact)");
    assertThat(src).contains(".optional(Contact::getPhone)");
  }

  @Test
  void groupOnAPageLessEventOpensABareFieldsScope() {
    // probate boFindMatchedCaseGrantRegistrarEscalation/caseMatches: the event places NO pages at all,
    // yet carries member overrides. EventBuilder.fields() returns the event's collection builder
    // without registering anything, so the emitter opens a bare .fields() purely to hang the
    // non-registering scope off — and only when there IS a scope to hang, so an event that genuinely
    // places nothing keeps its old terminating-statement shape.
    EventComplexTypeGroup.TypeRef caseMatch =
        EventComplexTypeGroup.TypeRef.builder().simpleName("CaseMatch").build();
    EventComplexTypeGroup group = EventComplexTypeGroup.builder()
        .eventId("escalate")
        .caseFieldId("caseMatches")
        .rootGetter("getCaseMatches")
        .rootElementType(caseMatch)
        .members(List.of(EventComplexTypeGroup.Member.builder()
            .hops(List.of())
            .leafType(caseMatch)
            .leafGetter("getCaseLink")
            .contextMethod("readonly")
            .build()))
        .build();

    EventModel escalate = EventModel.builder()
        .id("escalate").javaName("escalate").name("Escalate")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of())
        .build();
    // A second page-less event with NO group, to prove the bare .fields() is not emitted gratuitously.
    EventModel quiet = EventModel.builder()
        .id("quiet").javaName("quiet").name("Quiet")
        .preStates(List.of()).postState("Open").grants(Map.of()).pages(List.of())
        .build();
    FieldModel caseMatches = FieldModel.builder()
        .id("caseMatches").javaName("caseMatches").fieldType("Collection")
        .fieldTypeParameter("CaseMatch").build();
    CaseTypeModel model =
        modelWithEvents(List.of(escalate, quiet), List.of(caseMatches)).toBuilder()
            .eventComplexTypeGroups(Map.of("escalate\u001fcaseMatches", group))
            .build();

    String src = allSrc(new EventsConfigEmitter().emit(model, contextWith(40)));

    assertThat(src).contains("fields.complex(CaseData::getCaseMatches, CaseMatch.class)");
    assertThat(src).contains(".readonly(CaseMatch::getCaseLink)");
    // No placement is invented for the root, and the group's own event is the only one that gains a
    // fields builder.
    assertThat(src).doesNotContain("fields.readonly(CaseData::getCaseMatches)");
    assertThat(src).containsOnlyOnce("var fields = ");
  }
}
