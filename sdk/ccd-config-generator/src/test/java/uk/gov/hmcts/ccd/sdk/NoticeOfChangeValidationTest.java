package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.type.ChangeOrganisationRequest;

public class NoticeOfChangeValidationTest {

  enum State { Open }

  enum RoleWithAll implements HasRole {
    NOC_APPROVER("[NOCAPPROVER]"),
    CAA("caseworker-caa");

    private final String role;

    RoleWithAll(String role) {
      this.role = role;
    }

    @Override
    public String getRole() {
      return role;
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRU";
    }
  }

  enum RoleMissingNoc implements HasRole {
    CAA("caseworker-caa");

    private final String role;
    RoleMissingNoc(String role) { this.role = role; }
    @Override public String getRole() { return role; }
    @Override public String getCaseTypePermissions() { return "CRU"; }
  }

  enum RoleMissingCaa implements HasRole {
    NOC_APPROVER("[NOCAPPROVER]");

    private final String role;
    RoleMissingCaa(String role) { this.role = role; }
    @Override public String getRole() { return role; }
    @Override public String getCaseTypePermissions() { return "CRU"; }
  }

  static class CaseWithCoR {
    private String caseName;
    private ChangeOrganisationRequest<RoleWithAll> changeOrganisationRequestField;
    public String getCaseName() { return caseName; }
    public ChangeOrganisationRequest<RoleWithAll> getChangeOrganisationRequestField() {
      return changeOrganisationRequestField;
    }
  }

  static class CaseWithoutCoR {
    private String caseName;
    public String getCaseName() { return caseName; }
  }

  static class CaseWithCoRForMissingNocTest {
    private String caseName;
    private ChangeOrganisationRequest<RoleMissingNoc> changeOrganisationRequestField;
    public String getCaseName() { return caseName; }
    public ChangeOrganisationRequest<RoleMissingNoc> getChangeOrganisationRequestField() {
      return changeOrganisationRequestField;
    }
  }

  private <T, R extends HasRole> ConfigBuilderImpl<T, State, R> builderFor(Class<T> caseClass,
                                                                           Class<R> roleClass) {
    ResolvedCCDConfig<T, State, R> config = new ResolvedCCDConfig<>(
        caseClass, State.class, roleClass,
        new HashMap<>(), ImmutableSet.of(State.Open));
    return new ConfigBuilderImpl<>(config);
  }

  private AboutToStartOrSubmitResponse<CaseWithCoR, State> dummySubmitHandler(
      CaseDetails<CaseWithCoR, State> details, CaseDetails<CaseWithCoR, State> before) {
    return AboutToStartOrSubmitResponse.<CaseWithCoR, State>builder()
        .data(details.getData()).build();
  }

  @Test
  public void missingNocApproverRoleFailsBuild() {
    ConfigBuilderImpl<CaseWithCoRForMissingNocTest, State, RoleMissingNoc> b =
        builderFor(CaseWithCoRForMissingNocTest.class, RoleMissingNoc.class);
    b.noticeOfChange()
        .challenge("NoCChallenge")
        .question("caseName", "Enter case name")
        .answer(RoleMissingNoc.CAA).field(CaseWithCoRForMissingNocTest::getCaseName).done()
        .aboutToSubmitCallback((d, before) -> null);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("[NOCAPPROVER]");
  }

  @Test
  public void missingAboutToSubmitCallbackFailsBuild() {
    ConfigBuilderImpl<CaseWithCoR, State, RoleWithAll> b =
        builderFor(CaseWithCoR.class, RoleWithAll.class);
    b.noticeOfChange()
        .challenge("NoCChallenge")
        .question("caseName", "Enter case name")
        .answer(RoleWithAll.CAA).field(CaseWithCoR::getCaseName).done();

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("aboutToSubmitCallback");
  }

  @Test
  public void missingChangeOrganisationRequestFieldFailsBuild() {
    ConfigBuilderImpl<CaseWithoutCoR, State, RoleWithAll> b =
        builderFor(CaseWithoutCoR.class, RoleWithAll.class);
    b.noticeOfChange()
        .challenge("NoCChallenge")
        .question("caseName", "Enter case name")
        .answer(RoleWithAll.CAA).field(CaseWithoutCoR::getCaseName).done()
        .aboutToSubmitCallback((d, before) -> null);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ChangeOrganisationRequest");
  }

  @Test
  public void missingCaaRoleFailsBuild() {
    ConfigBuilderImpl<CaseWithCoR, State, RoleMissingCaa> b =
        new ConfigBuilderImpl<>(new ResolvedCCDConfig<>(
            CaseWithCoR.class, State.class, RoleMissingCaa.class,
            new HashMap<>(), ImmutableSet.of(State.Open)));
    b.noticeOfChange()
        .challenge("NoCChallenge")
        .question("caseName", "Enter case name")
        .answer(RoleMissingCaa.NOC_APPROVER).field(CaseWithCoR::getCaseName).done()
        .aboutToSubmitCallback((d, before) -> null);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("caseworker-caa");
  }

  @Test
  public void emptyNoticeOfChangeBuildsSuccessfully() {
    ConfigBuilderImpl<CaseWithoutCoR, State, RoleWithAll> b =
        builderFor(CaseWithoutCoR.class, RoleWithAll.class);
    b.noticeOfChange();

    ResolvedCCDConfig<CaseWithoutCoR, State, RoleWithAll> config = b.build();
    assertThat(config.getEvents()).isEmpty();
  }

  @Test
  public void collisionCheckFailsWhenTwoEventsVisibleToNocApprover() {
    ConfigBuilderImpl<CaseWithCoR, State, RoleWithAll> b =
        builderFor(CaseWithCoR.class, RoleWithAll.class);
    b.noticeOfChange()
        .challenge("NoCChallenge")
        .question("caseName", "Enter case name")
        .answer(RoleWithAll.CAA).field(CaseWithCoR::getCaseName).done()
        .aboutToSubmitCallback((d, before) -> null);
    // second event also granted to NOCAPPROVER — should trip the collision check
    b.event("some-other-event")
        .forAllStates()
        .name("Some other event")
        .grant(Permission.CRU, RoleWithAll.NOC_APPROVER);

    assertThatThrownBy(b::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("[NOCAPPROVER]")
        .hasMessageContaining("exactly one");
  }

  @Test
  public void manualApplyEventDeclarationSkipsAutoRegistration() {
    ConfigBuilderImpl<CaseWithCoR, State, RoleWithAll> b =
        builderFor(CaseWithCoR.class, RoleWithAll.class);
    // Manually declare the apply event BEFORE calling noticeOfChange — auto-registration should
    // detect the existing entry and skip duplicate registration.
    b.event("notice-of-change-applied")
        .forAllStates()
        .name("Custom Apply Handler")
        .grant(Permission.CRU, RoleWithAll.NOC_APPROVER);

    b.noticeOfChange()
        .challenge("NoCChallenge")
        .question("caseName", "Enter case name")
        .answer(RoleWithAll.CAA).field(CaseWithCoR::getCaseName).done()
        .aboutToSubmitCallback((d, before) -> null);  // ignored because manual declaration wins

    ResolvedCCDConfig<CaseWithCoR, State, RoleWithAll> config = b.build();
    assertThat(config.getEvents()).containsKey("notice-of-change-applied");
    assertThat(config.getEvents().get("notice-of-change-applied").getName())
        .isEqualTo("Custom Apply Handler");
  }
}
