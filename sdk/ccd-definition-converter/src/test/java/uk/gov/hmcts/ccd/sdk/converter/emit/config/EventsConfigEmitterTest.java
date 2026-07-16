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
  void eventWithPagesEmitsFieldsChainAndPageClass() {
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

    // The event class terminates its header at .fields() and delegates the page to its own class
    // in <root>.event.page (finding #2).
    String eventSrc = classNamed(files, "CreateCase");
    assertThat(eventSrc).contains(".fields()");
    assertThat(eventSrc).contains(".apply(fields)");
    String pageSrc = classNamed(files, "CreateCasePage1");
    assertThat(pageSrc).contains("package " + EnvironmentFlagsEmitterTest.CONFIG_PKG
        + ".event.page");
    assertThat(pageSrc).contains(".page(\"1\")");
    assertThat(pageSrc).contains(".mandatory(");
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
}
