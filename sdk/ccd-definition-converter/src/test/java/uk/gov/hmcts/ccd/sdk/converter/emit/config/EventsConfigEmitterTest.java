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

  @Test
  void singleEventFitsInOneClass() {
    List<JavaFile> files = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40));
    assertThat(files).hasSize(1);
  }

  @Test
  void singleChunkClassNameHasNoNumberSuffix() {
    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40))
        .get(0).toString();
    assertThat(src).contains("class MinimalEventsConfig");
    assertThat(src).doesNotContain("MinimalEventsConfig01");
  }

  @Test
  void exceedingChunkSizeProducesMultipleFiles() {
    List<EventModel> events = new ArrayList<>();
    for (int ii = 0; ii < 5; ii++) {
      events.add(simpleEvent("event" + ii));
    }
    List<JavaFile> files = new EventsConfigEmitter()
        .emit(modelWithEvents(events), contextWith(2));
    // 5 events at chunk size 2 → 3 EventsConfigNN chunks. All five share one grant map (caseworker
    // -test CRUD), which — being reused by ≥ 3 events — is factored into a single shared
    // MinimalEventGrants helper class (finding #11), so the emitter also returns that 4th file.
    List<String> chunkClasses = files.stream()
        .map(f -> f.typeSpec().name())
        .filter(n -> n.startsWith("MinimalEventsConfig"))
        .toList();
    assertThat(chunkClasses).hasSize(3);
    assertThat(files).hasSize(4);
  }

  @Test
  void multipleChunkClassNamesAreNumbered() {
    List<EventModel> events = List.of(simpleEvent("ev1"), simpleEvent("ev2"), simpleEvent("ev3"));
    List<String> names = new EventsConfigEmitter()
        .emit(modelWithEvents(events), contextWith(2))
        .stream()
        .map(JavaFile::toString)
        .toList();
    assertThat(names.get(0)).contains("class MinimalEventsConfig01");
    assertThat(names.get(1)).contains("class MinimalEventsConfig02");
  }

  @Test
  void generatedClassIsInConfigPackage() {
    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40))
        .get(0).toString();
    assertThat(src).contains("package " + EnvironmentFlagsEmitterTest.CONFIG_PKG);
  }

  @Test
  void generatedClassImplementsCcdConfig() {
    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40))
        .get(0).toString();
    assertThat(src).contains("implements CCDConfig");
  }

  @Test
  void generatedConfigureCallsEventMethods() {
    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40))
        .get(0).toString();
    assertThat(src).contains("createCase(builder)");
  }

  @Test
  void eventRegistrationContainsEventId() {
    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40))
        .get(0).toString();
    assertThat(src).contains("builder.event(\"createCase\")");
  }

  @Test
  void eventGrantsAreEmitted() {
    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40))
        .get(0).toString();
    assertThat(src).contains("UserRole.CASEWORKER_TEST");
  }

  @Test
  void everyEventEmitsExplicitGrants() {
    // The converter emits .explicitGrants() on every event so event grants never cascade onto the
    // fields the event places; a converted config reproduces the input's AuthorisationCaseField
    // grants exactly rather than the SDK's event-grant union.
    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(simpleEvent("createCase"))),
            contextWith(40))
        .get(0).toString();
    assertThat(src).contains(".explicitGrants()");
  }

  @Test
  void eventWithPagesEmitsFieldsChain() {
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

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(applicantName)),
            contextWith(40))
        .get(0).toString();

    assertThat(src).contains(".fields()");
    assertThat(src).contains(".page(\"1\")");
    assertThat(src).contains(".mandatory(");
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

    String src = new EventsConfigEmitter().emit(model, context).get(0).toString();

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

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event)), contextWith(40))
        .get(0).toString();

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

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event)), contextWith(40))
        .get(0).toString();

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

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(caseNotes2)), contextWith(40))
        .get(0).toString();

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

    String src = new EventsConfigEmitter()
        .emit(modelWithEvents(List.of(event), List.of(infoLabel)), contextWith(40))
        .get(0).toString();

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

    String src = new EventsConfigEmitter()
        .emit(model, contextWith(40))
        .get(0).toString();

    assertThat(src).contains("UserRole.CREATOR");
  }
}
