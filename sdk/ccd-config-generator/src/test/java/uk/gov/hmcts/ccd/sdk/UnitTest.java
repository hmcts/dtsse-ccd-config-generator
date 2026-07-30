package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.AccessType;
import uk.gov.hmcts.ccd.sdk.api.CCDAccessGroup;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.example.missingcomplex.Applicant;
import uk.gov.hmcts.example.missingcomplex.MissingComplex;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

  /**
   * The golden config comparison strips DisplayOrder, so these assert it directly: the definition
   * store rejects an import whose AccessType rows share a DisplayOrder.
   */
  @Test
  public void accessGroupTakesConsecutiveDisplayOrderPerOrganisationProfile() {
    ResolvedCCDConfig<CaseData, State, UserRole> resolved = resolve(
        new TestAccessGroup("all-cases", List.of("A_PROFILE", "B_PROFILE", "C_PROFILE"), 5));

    assertThat(resolved.getAccessTypes())
        .extracting(AccessType::getOrganisationProfileId, AccessType::getDisplayOrder)
        .containsExactly(tuple("A_PROFILE", 5), tuple("B_PROFILE", 6), tuple("C_PROFILE", 7));
  }

  @Test
  public void accessGroupsSharingADisplayOrderAreRejected() {
    assertThatThrownBy(() -> resolve(
        new TestAccessGroup("all-cases", List.of("A_PROFILE", "B_PROFILE"), 1),
        new TestAccessGroup("create-cases", List.of("C_PROFILE"), 2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reuses DisplayOrder 2");
  }

  @SafeVarargs
  private final ResolvedCCDConfig<CaseData, State, UserRole> resolve(CCDAccessGroup<CaseData>... groups) {
    class AccessGroupConfig implements CCDConfig<CaseData, State, UserRole> {
      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.accessGroups(groups);
      }
    }

    return new ConfigResolver<CaseData, State, UserRole>(List.of(new AccessGroupConfig()))
        .resolveCCDConfig();
  }

  private record TestAccessGroup(String getAccessTypeId, List<String> getOrganisationProfileIds,
                                 int getDisplayOrder) implements CCDAccessGroup<CaseData> {

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
      return getAccessTypeId + " description";
    }

    @Override
    public String getHintText() {
      return getAccessTypeId + " hint";
    }
  }
}
