package uk.gov.hmcts.ccd.sdk.converter.emit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.RoleModel;
import uk.gov.hmcts.ccd.sdk.converter.model.SearchFieldModel;
import uk.gov.hmcts.ccd.sdk.converter.model.TabModel;

/**
 * Tests for {@link CoreConfigEmitter}.
 */
class CoreConfigEmitterTest {

  private static CaseTypeModel minimalModel() {
    return EnvironmentFlagsEmitterTest.minimalModel();
  }

  private static CaseTypeModel modelWithTabs() {
    TabModel.TabField field = TabModel.TabField.builder()
        .caseFieldId("applicantName")
        .displayOrder(1)
        .build();
    TabModel tab = TabModel.builder()
        .tabId("summary")
        .label("Summary")
        .displayOrder(1)
        .fields(List.of(field))
        .build();
    return withTabs(minimalModel(), List.of(tab));
  }

  private static CaseTypeModel withTabs(CaseTypeModel base, List<TabModel> tabs) {
    return CaseTypeModel.builder()
        .caseTypeId(base.getCaseTypeId())
        .caseTypeName(base.getCaseTypeName())
        .caseTypeDescription(base.getCaseTypeDescription())
        .jurisdictionId(base.getJurisdictionId())
        .jurisdictionName(base.getJurisdictionName())
        .jurisdictionDescription(base.getJurisdictionDescription())
        .states(base.getStates())
        .roles(base.getRoles())
        .caseFields(base.getCaseFields())
        .complexTypes(base.getComplexTypes())
        .fixedLists(base.getFixedLists())
        .events(base.getEvents())
        .tabs(tabs)
        .searchInputFields(base.getSearchInputFields())
        .searchResultFields(base.getSearchResultFields())
        .workBasketInputFields(base.getWorkBasketInputFields())
        .workBasketResultFields(base.getWorkBasketResultFields())
        .searchCasesResultFields(base.getSearchCasesResultFields())
        .stateAuthorisations(base.getStateAuthorisations())
        .accessClasses(base.getAccessClasses())
        .searchCriteria(base.getSearchCriteria())
        .searchParties(base.getSearchParties())
        .challengeQuestions(base.getChallengeQuestions())
        .roleToAccessProfiles(base.getRoleToAccessProfiles())
        .categories(base.getCategories())
        .passthroughSheets(base.getPassthroughSheets())
        .build();
  }

  private static CaseTypeModel modelWithStateGrants() {
    Map<String, Object> cols = new LinkedHashMap<>();
    cols.put("CaseStateID", "Open");
    cols.put("UserRole", "caseworker-test");
    cols.put("CRUD", "CRUD");
    SheetRow row = SheetRow.builder()
        .sheet(SheetName.AUTHORISATION_CASE_STATE)
        .columns(cols)
        .overlayTags(Set.of())
        .source(null)
        .build();
    return CaseTypeModel.builder()
        .caseTypeId("Minimal")
        .caseTypeName("Minimal Case")
        .caseTypeDescription("Test")
        .jurisdictionId("TEST")
        .jurisdictionName("Test Jurisdiction")
        .jurisdictionDescription("Fixture jurisdiction")
        .states(List.of())
        .roles(List.of(RoleModel.builder()
            .id("caseworker-test")
            .javaConstant("CASEWORKER_TEST")
            .caseTypePermissions("")
            .caseRole(false)
            .build()))
        .caseFields(List.of())
        .complexTypes(List.of())
        .fixedLists(List.of())
        .events(List.of())
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of())
        .workBasketResultFields(List.of())
        .searchCasesResultFields(List.of())
        .stateAuthorisations(List.of(row))
        .accessClasses(List.of())
        .searchCriteria(List.of())
        .searchParties(List.of())
        .challengeQuestions(List.of())
        .roleToAccessProfiles(List.of())
        .categories(List.of())
        .passthroughSheets(List.of())
        .build();
  }

  private static CaseTypeModel modelWithWorkBasket() {
    SearchFieldModel field = SearchFieldModel.builder()
        .caseFieldId("applicantName")
        .label("Applicant name")
        .displayOrder(1)
        .build();
    return CaseTypeModel.builder()
        .caseTypeId("Minimal")
        .caseTypeName("Minimal Case")
        .caseTypeDescription("Test")
        .jurisdictionId("TEST")
        .jurisdictionName("Test Jurisdiction")
        .jurisdictionDescription("Fixture jurisdiction")
        .states(List.of())
        .roles(List.of())
        .caseFields(List.of())
        .complexTypes(List.of())
        .fixedLists(List.of())
        .events(List.of())
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of(field))
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

  private static CaseTypeModel modelWithRoleScopedSearch() {
    SearchFieldModel unscoped = SearchFieldModel.builder()
        .caseFieldId("applicantName")
        .label("Applicant name")
        .displayOrder(1)
        .build();
    SearchFieldModel scoped = SearchFieldModel.builder()
        .caseFieldId("claimType")
        .label("Type of claim")
        .displayOrder(2)
        .userRole("caseworker-test")
        .build();
    return CaseTypeModel.builder()
        .caseTypeId("Minimal")
        .caseTypeName("Minimal Case")
        .caseTypeDescription("Test")
        .jurisdictionId("TEST")
        .jurisdictionName("Test Jurisdiction")
        .jurisdictionDescription("Fixture jurisdiction")
        .states(List.of())
        .roles(List.of(RoleModel.builder()
            .id("caseworker-test")
            .javaConstant("CASEWORKER_TEST")
            .caseTypePermissions("")
            .caseRole(false)
            .build()))
        .caseFields(List.of())
        .complexTypes(List.of())
        .fixedLists(List.of())
        .events(List.of())
        .tabs(List.of())
        .searchInputFields(List.of(unscoped, scoped))
        .searchResultFields(List.of())
        .workBasketInputFields(List.of(unscoped, scoped))
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

  /** The concatenated source of every emitted file, for content assertions across the split beans. */
  private static String allSrc(CaseTypeModel model) {
    return new CoreConfigEmitter().emit(model, EnvironmentFlagsEmitterTest.context()).stream()
        .map(JavaFile::toString)
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static String classNamed(CaseTypeModel model, String simpleName) {
    return new CoreConfigEmitter().emit(model, EnvironmentFlagsEmitterTest.context()).stream()
        .filter(f -> f.typeSpec().name().equals(simpleName))
        .map(JavaFile::toString)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no emitted class named " + simpleName));
  }

  @Test
  void caseTypeBeanAlwaysEmitted() {
    // The monolith is split by concern (finding #6): identity/jurisdiction lives in its own
    // <Prefix>CaseType bean, always present even when a case type carries no tabs/search/grants.
    List<JavaFile> files = new CoreConfigEmitter().emit(minimalModel(),
        EnvironmentFlagsEmitterTest.context());
    assertThat(files).anySatisfy(f -> assertThat(f.typeSpec().name()).isEqualTo("MinimalCaseType"));
  }

  @Test
  void concernsSplitIntoSeparateBeans() {
    // A model carrying identity + tabs emits distinct CaseType and Tabs beans, not one CoreConfig.
    List<String> names = new CoreConfigEmitter().emit(modelWithTabs(),
            EnvironmentFlagsEmitterTest.context()).stream()
        .map(f -> f.typeSpec().name())
        .toList();
    assertThat(names).contains("MinimalCaseType", "MinimalTabs");
    assertThat(names).doesNotContain("MinimalCoreConfig");
  }

  @Test
  void generatedClassIsInConfigPackage() {
    assertThat(classNamed(minimalModel(), "MinimalCaseType"))
        .contains("package " + EnvironmentFlagsEmitterTest.CONFIG_PKG);
  }

  @Test
  void generatedClassImplementsCcdConfig() {
    assertThat(classNamed(minimalModel(), "MinimalCaseType")).contains("implements CCDConfig");
  }

  @Test
  void generatedConfigureEmitsCaseType() {
    assertThat(classNamed(minimalModel(), "MinimalCaseType"))
        .contains("builder.caseType(\"Minimal\"");
  }

  @Test
  void generatedConfigureEmitsJurisdiction() {
    assertThat(classNamed(minimalModel(), "MinimalCaseType"))
        .contains("builder.jurisdiction(\"TEST\"");
  }

  @Test
  void tabEmittedForModelWithTabs() {
    String src = classNamed(modelWithTabs(), "MinimalTabs");
    assertThat(src).contains("builder.tab(\"summary\"");
    assertThat(src).contains(".field(\"applicantName\")");
  }

  @Test
  void stateGrantEmittedForStateAuthorisationRow() {
    String src = classNamed(modelWithStateGrants(), "MinimalGrants");
    assertThat(src).contains("builder.grant(State.Open");
    assertThat(src).contains("UserRole.CASEWORKER_TEST");
  }

  @Test
  void stateGrantForCaseRoleUsesJavaConstantFromRoleModel() {
    Map<String, Object> cols = new LinkedHashMap<>();
    cols.put("CaseStateID", "Open");
    cols.put("UserRole", "[CREATOR]");
    cols.put("CRUD", "R");
    SheetRow row = SheetRow.builder()
        .sheet(SheetName.AUTHORISATION_CASE_STATE)
        .columns(cols)
        .overlayTags(Set.of())
        .source(null)
        .build();
    CaseTypeModel model = CaseTypeModel.builder()
        .caseTypeId("Minimal")
        .caseTypeName("Minimal Case")
        .caseTypeDescription("Test")
        .jurisdictionId("TEST")
        .jurisdictionName("Test Jurisdiction")
        .jurisdictionDescription("Fixture jurisdiction")
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
        .events(List.of())
        .tabs(List.of())
        .searchInputFields(List.of())
        .searchResultFields(List.of())
        .workBasketInputFields(List.of())
        .workBasketResultFields(List.of())
        .searchCasesResultFields(List.of())
        .stateAuthorisations(List.of(row))
        .accessClasses(List.of())
        .searchCriteria(List.of())
        .searchParties(List.of())
        .challengeQuestions(List.of())
        .roleToAccessProfiles(List.of())
        .categories(List.of())
        .passthroughSheets(List.of())
        .build();

    String src = classNamed(model, "MinimalGrants");
    assertThat(src).contains("UserRole.CREATOR");
  }

  @Test
  void explicitStateGrantsAlwaysEmitted() {
    // A converted config reproduces the input's AuthorisationCaseState exactly, so the CaseType bean
    // must opt out of the SDK's event-derived state-permission broadening unconditionally.
    assertThat(classNamed(minimalModel(), "MinimalCaseType"))
        .contains("builder.explicitStateGrants()");
  }

  @Test
  void workBasketInputFieldEmitted() {
    String src = classNamed(modelWithWorkBasket(), "MinimalWorkBasket");
    assertThat(src).contains("builder.workBasketInputFields()");
    assertThat(src).contains(".field(\"applicantName\"");
  }

  @Test
  void roleScopedSearchFieldUsesThreeArgOverload() {
    // A search/workbasket row carrying a UserRole must be emitted via the role-scoped
    // field(id, label, role) overload; unscoped rows keep the two-arg form.
    String workBasket = classNamed(modelWithRoleScopedSearch(), "MinimalWorkBasket");
    assertThat(workBasket).contains("builder.workBasketInputFields()");
    assertThat(workBasket).contains(".field(\"applicantName\", \"Applicant name\")");
    assertThat(workBasket).contains(
        ".field(\"claimType\", \"Type of claim\", UserRole.CASEWORKER_TEST)");
    assertThat(classNamed(modelWithRoleScopedSearch(), "MinimalSearch"))
        .contains("builder.searchInputFields()");
  }

  @Test
  void noSetCallbackHostEmitted() {
    // The converter emits no SDK callback wiring, so no setCallbackHost is emitted; every callback
    // column is carried through verbatim by passthrough instead.
    assertThat(allSrc(minimalModel())).doesNotContain("setCallbackHost");
  }
}
