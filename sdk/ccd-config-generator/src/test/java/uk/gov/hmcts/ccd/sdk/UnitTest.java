package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.noc.NocOrganisation;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.example.missingcomplex.Applicant;
import uk.gov.hmcts.example.missingcomplex.MissingComplex;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UnitTest {

  @Test
  public void npeBug() {
    class NPEBug implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.event("addNotes")
          .forStates(State.Submitted, State.Open, State.Deleted)
          .fields()
          .readonly(CaseData::getProceeding)
          .complex(CaseData::getJudgeAndLegalAdvisor);
      }
    }

    ConfigResolver<CaseData, State, UserRole> generator = new ConfigResolver<>(List.of(new NPEBug()));
    generator.resolveCCDConfig();
  }

  @Test
  public void missingComplexBug() {
    class MissingBug implements CCDConfig<MissingComplex, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<MissingComplex, State, UserRole> builder) {
      }
    }

    ConfigResolver<MissingComplex, State, UserRole> generator = new ConfigResolver<>(List.of(new MissingBug()));
    ResolvedCCDConfig<MissingComplex, State, UserRole> resolved = generator.resolveCCDConfig();
    assertThat(resolved.types).containsKeys(Applicant.class);
  }

  @Test
  public void configBuilderCanConfigureNoticeOfChangeRuntimeHandlers() {
    class NocConfig implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
        builder.noticeOfChange()
            .validate((context, request) -> uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersResponse.verified(
                new NocOrganisation("ORG1", "Org 1")
            ))
            .submit((context, request) -> NocSubmissionResponse.approved("[DEFENDANTSOLICITOR]"));
      }
    }

    ConfigResolver<CaseData, State, UserRole> generator = new ConfigResolver<>(List.of(new NocConfig()));
    ResolvedCCDConfig<CaseData, State, UserRole> resolved = generator.resolveCCDConfig();

    assertThat(resolved.getNoticeOfChange().getEndpoint()).isNotNull();
    assertThat(resolved.getNoticeOfChange().getEndpoint().caseTypeId()).isEqualTo("TEST");
    assertThat(resolved.getNoticeOfChange().getEndpoint().isAuthorisedService("xui_webapp")).isTrue();
  }

  @Test
  public void configBuilderRequiresBothNoticeOfChangeRuntimeHandlers() {
    class NocConfig implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
        builder.noticeOfChange()
            .submit((context, request) -> NocSubmissionResponse.approved("[DEFENDANTSOLICITOR]"));
      }
    }

    ConfigResolver<CaseData, State, UserRole> generator = new ConfigResolver<>(List.of(new NocConfig()));

    assertThatThrownBy(generator::resolveCCDConfig)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Notice of Change validation and submission handlers must both be configured");
  }

  /**
   * An access group and the role class that declares it reference each other, so a role captured as a
   * constructor argument is read during a circular static initialisation and comes out null. Left
   * unchecked that emits an empty CaseAssignedRoleField, which imports cleanly and grants nothing.
   */
  @Test
  public void rejectsAnAccessGroupWhoseRoleFailedToResolve() {
    class UnresolvedConfig implements CCDConfig<CaseData, State, UnresolvedRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UnresolvedRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
      }
    }

    ConfigResolver<CaseData, State, UnresolvedRole> generator =
        new ConfigResolver<>(List.of(new UnresolvedConfig()));

    assertThatThrownBy(generator::resolveCCDConfig)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("caseAssignedRoleField")
        .hasMessageContaining("circular enum initialisation");
  }

  /**
   * Without a RoleToAccessProfiles mapping the group role assignment PRM mints resolves to no access
   * profile, leaving the access type inert. The definition store does not check GroupRoleName against
   * that sheet, so the SDK has to.
   */
  @Test
  public void rejectsAnAccessGroupWithNoAccessProfiles() {
    class NoProfilesConfig implements CCDConfig<CaseData, State, NoProfilesRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, NoProfilesRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
      }
    }

    ConfigResolver<CaseData, State, NoProfilesRole> generator =
        new ConfigResolver<>(List.of(new NoProfilesConfig()));

    assertThatThrownBy(generator::resolveCCDConfig)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must declare at least one access profile");
  }

  /** A role whose access group never resolves its caseAssignedRoleField. */
  private enum UnresolvedRole implements HasRole {
    ATTACHED;

    @Override
    public String getRole() {
      return "[ATTACHED]";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRU";
    }

    @Override
    public CCDAccessGroup getAccessGroup() {
      return new TestAccessGroup(null, List.of("access-profile"));
    }
  }

  /** A role whose access group declares no access profiles for its group role. */
  private enum NoProfilesRole implements HasRole {
    ATTACHED;

    @Override
    public String getRole() {
      return "[ATTACHED]";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRU";
    }

    @Override
    public CCDAccessGroup getAccessGroup() {
      return new TestAccessGroup(UserRole.CASE_ACCESS_APPROVER_GROUP, List.of());
    }
  }

  private record TestAccessGroup(HasRole caseAssignedRoleField, List<String> accessProfiles)
      implements CCDAccessGroup {

    @Override
    public String getAccessTypeId() {
      return "TEST_ACCESS_TYPE";
    }

    @Override
    public String getOrganisationProfileId() {
      return "SOLICITOR_PROFILE";
    }

    @Override
    public boolean isAccessMandatory() {
      return false;
    }

    @Override
    public boolean isAccessDefault() {
      return false;
    }

    @Override
    public boolean isDisplay() {
      return true;
    }

    @Override
    public String getDescription() {
      return "description";
    }

    @Override
    public String getHintText() {
      return "hint";
    }

    @Override
    public int getDisplayOrder() {
      return 1;
    }

    @Override
    public HasRole getGroupRoleName() {
      return UserRole.CASE_ACCESS_APPROVER_GROUP;
    }

    @Override
    public HasRole getCaseAssignedRoleField() {
      return caseAssignedRoleField;
    }

    @Override
    public List<String> getGroupRoleAccessProfiles() {
      return accessProfiles;
    }

    @Override
    public boolean isGroupAccessEnabled() {
      return true;
    }

    @Override
    public String getCaseAccessGroupIdTemplate() {
      return "TEST:TEST:caseworker-approver-group:$ORGID$";
    }
  }
}
