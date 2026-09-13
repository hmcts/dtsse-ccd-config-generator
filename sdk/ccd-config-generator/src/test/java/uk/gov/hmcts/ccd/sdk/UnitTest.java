package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.AccessType;
import uk.gov.hmcts.ccd.sdk.api.AccessTypeRole;
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
import static org.assertj.core.groups.Tuple.tuple;

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

  @Test
  public void defaultsAccessProfilesToTheRoleName() {
    class DefaultProfilesConfig implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
        builder.caseRoleToAccessProfile(UserRole.LOCAL_AUTHORITY);
      }
    }

    ConfigResolver<CaseData, State, UserRole> generator =
        new ConfigResolver<>(List.of(new DefaultProfilesConfig()));
    ResolvedCCDConfig<CaseData, State, UserRole> resolved = generator.resolveCCDConfig();

    List<String> profiles = resolved.getCaseRoleToAccessProfiles().stream()
        .filter(p -> p.getRole().getRole().equals(UserRole.LOCAL_AUTHORITY.getRole()))
        .findFirst().orElseThrow().getAccessProfiles();
    assertThat(profiles).containsExactly(UserRole.LOCAL_AUTHORITY.getRole());
  }

  @Test
  public void explicitAccessProfilesReplaceDefault() {
    class ExplicitProfilesConfig implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
        builder.caseRoleToAccessProfile(UserRole.LOCAL_AUTHORITY)
            .accessProfiles("custom-profile-1", "custom-profile-2");
      }
    }

    ConfigResolver<CaseData, State, UserRole> generator =
        new ConfigResolver<>(List.of(new ExplicitProfilesConfig()));
    ResolvedCCDConfig<CaseData, State, UserRole> resolved = generator.resolveCCDConfig();

    List<String> profiles = resolved.getCaseRoleToAccessProfiles().stream()
        .filter(p -> p.getRole().getRole().equals(UserRole.LOCAL_AUTHORITY.getRole()))
        .findFirst().orElseThrow().getAccessProfiles();
    assertThat(profiles).containsExactly("custom-profile-1", "custom-profile-2");
  }

  @Test
  public void derivesRoleToAccessProfilesUsingTheRoleName() {
    class DerivedConfig implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
      }
    }

    ConfigResolver<CaseData, State, UserRole> generator =
        new ConfigResolver<>(List.of(new DerivedConfig()));
    ResolvedCCDConfig<CaseData, State, UserRole> resolved = generator.resolveCCDConfig();

    List<String> profiles = resolved.getCaseRoleToAccessProfiles().stream()
        .filter(p -> p.getRole().getRole().equals(UserRole.CASE_ACCESS_APPROVER.getRole()))
        .findFirst().orElseThrow().getAccessProfiles();
    assertThat(profiles).containsExactly(UserRole.CASE_ACCESS_APPROVER.getRole());
  }

  /**
   * The definition store keys an access type on (caseType, jurisdiction, accessTypeId,
   * organisationProfileId), so one access type offered to several organisation profiles is several
   * rows. Deriving on accessTypeId alone kept only the first.
   */
  @Test
  public void derivesOneAccessTypePerOrganisationProfile() {
    class MultiProfileConfig implements CCDConfig<CaseData, State, MultiProfileRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, MultiProfileRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
      }
    }

    ResolvedCCDConfig<CaseData, State, MultiProfileRole> resolved =
        new ConfigResolver<>(List.of(new MultiProfileConfig())).resolveCCDConfig();

    assertThat(resolved.getAccessTypes())
        .extracting(AccessType::getAccessTypeId, AccessType::getOrganisationProfileId)
        .containsExactly(
            tuple("TEST_ACCESS_TYPE", "SOLICITOR_PROFILE"),
            tuple("TEST_ACCESS_TYPE", "LOCALAUTH_PROFILE"));
    assertThat(resolved.getAccessTypeRoles())
        .extracting(AccessTypeRole::getAccessTypeId, AccessTypeRole::getOrganisationProfileId)
        .containsExactly(
            tuple("TEST_ACCESS_TYPE", "SOLICITOR_PROFILE"),
            tuple("TEST_ACCESS_TYPE", "LOCALAUTH_PROFILE"));
  }

  /**
   * The attaching role's name goes in GroupRoleName when the group mints a group role per
   * organisation, and in OrganisationalRoleName when it does not. The definition store rejects a
   * GroupRoleName whose GroupAccessEnabled is unset, so the two have to move together.
   */
  @Test
  public void namesTheAttachingRoleAccordingToGroupAccessEnabled() {
    class MixedConfig implements CCDConfig<CaseData, State, MixedGroupAccessRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, MixedGroupAccessRole> builder) {
        builder.caseType("TEST", "Test", "Test case type");
      }
    }

    ResolvedCCDConfig<CaseData, State, MixedGroupAccessRole> resolved =
        new ConfigResolver<>(List.of(new MixedConfig())).resolveCCDConfig();

    assertThat(resolved.getAccessTypeRoles())
        .extracting(AccessTypeRole::getOrganisationProfileId, AccessTypeRole::isGroupAccessEnabled,
            AccessTypeRole::getGroupRoleName, AccessTypeRole::getOrganisationalRoleName)
        .containsExactly(
            tuple("SOLICITOR_PROFILE", true, "[ATTACHED]", null),
            tuple("LOCALAUTH_PROFILE", false, null, "[ATTACHED]"));
  }

  /** A role carrying one access type across two organisation profiles. */
  private enum MultiProfileRole implements HasRole {
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
    public List<CCDAccessGroup> getAccessGroups() {
      return List.of(
          new TestAccessGroup("SOLICITOR_PROFILE", true),
          new TestAccessGroup("LOCALAUTH_PROFILE", true));
    }
  }

  /** A role carrying one group-access-enabled group and one that is not. */
  private enum MixedGroupAccessRole implements HasRole {
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
    public List<CCDAccessGroup> getAccessGroups() {
      return List.of(
          new TestAccessGroup("SOLICITOR_PROFILE", true),
          new TestAccessGroup("LOCALAUTH_PROFILE", false));
    }
  }

  private record TestAccessGroup(String organisationProfileId, boolean groupAccessEnabled)
      implements CCDAccessGroup {

    @Override
    public String getAccessTypeId() {
      return "TEST_ACCESS_TYPE";
    }

    @Override
    public String getOrganisationProfileId() {
      return organisationProfileId;
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
    public String getCaseAssignedRoleField() {
      return "[SOLICITOR]";
    }

    @Override
    public boolean isGroupAccessEnabled() {
      return groupAccessEnabled;
    }

    @Override
    public String getCaseAccessGroupIdTemplate() {
      return "TEST:TEST:[ATTACHED]:$ORGID$";
    }
  }
}
