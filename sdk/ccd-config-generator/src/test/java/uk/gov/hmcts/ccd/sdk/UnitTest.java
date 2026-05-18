package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.DisplayContext;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.example.missingcomplex.Applicant;
import uk.gov.hmcts.example.missingcomplex.MissingComplex;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

import static org.assertj.core.api.Assertions.assertThat;

public class UnitTest {

  private enum TestState {
    State
  }

  private enum TestRole implements HasRole {
    ROLE;

    @Override
    public String getRole() {
      return "role";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }

  private static class TestCaseData {
    private String caseFlags;
    private String flagLauncher;

    public String getCaseFlags() {
      return caseFlags;
    }

    public String getFlagLauncher() {
      return flagLauncher;
    }
  }

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
  public void eventDefaultsApplyUnlessEventOverridesThem() {
    class DefaultsConfig implements CCDConfig<TestCaseData, TestState, TestRole> {
      @Override
      public void configure(ConfigBuilder<TestCaseData, TestState, TestRole> builder) {
        builder.eventDefaults()
            .omitLiveFrom()
            .omitPublish()
            .noEndButtonLabel();

        builder.event("defaulted")
            .forAllStates()
            .name("Defaulted");

        builder.event("overridden")
            .forAllStates()
            .name("Overridden")
            .includeLiveFrom()
            .publishToCamunda()
            .endButtonLabel("Continue");
      }
    }

    ConfigResolver<TestCaseData, TestState, TestRole> generator = new ConfigResolver<>(List.of(new DefaultsConfig()));
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = generator.resolveCCDConfig();

    assertThat(resolved.events.get("defaulted").isOmitLiveFrom()).isTrue();
    assertThat(resolved.events.get("defaulted").isOmitPublish()).isTrue();
    assertThat(resolved.events.get("defaulted").getEndButtonLabel()).isEmpty();

    assertThat(resolved.events.get("overridden").isOmitLiveFrom()).isFalse();
    assertThat(resolved.events.get("overridden").isOmitPublish()).isFalse();
    assertThat(resolved.events.get("overridden").isPublishToCamunda()).isTrue();
    assertThat(resolved.events.get("overridden").getEndButtonLabel()).isEqualTo("Continue");
  }

  @Test
  public void caseFlagFieldHelpersApplyCcdFlagLauncherConventions() {
    class FlagConfig implements CCDConfig<TestCaseData, TestState, TestRole> {
      @Override
      public void configure(ConfigBuilder<TestCaseData, TestState, TestRole> builder) {
        builder.event("createFlag")
            .forAllStates()
            .fields()
            .field(TestCaseData::getCaseFlags)
            .caseFlagBackingField()
            .done()
            .field(TestCaseData::getFlagLauncher)
            .flagLauncherCreate();
      }
    }

    ConfigResolver<TestCaseData, TestState, TestRole> generator = new ConfigResolver<>(List.of(new FlagConfig()));
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = generator.resolveCCDConfig();

    Field<?, ?, ?, ?> caseFlags = resolved.events.get("createFlag").getFields().getFields().get(0).build();
    assertThat(caseFlags.getContext()).isEqualTo(DisplayContext.Optional);
    assertThat(caseFlags.getShowCondition()).isEqualTo("flagLauncher=\"hidden\"");
    assertThat(caseFlags.getCaseEventColumns())
        .containsEntry("PageColumnNumber", null)
        .containsEntry("RetainHiddenValue", "Yes");

    Field<?, ?, ?, ?> flagLauncher = resolved.events.get("createFlag").getFields().getFields().get(1).build();
    assertThat(flagLauncher.getContext()).isEqualTo(DisplayContext.Optional);
    assertThat(flagLauncher.getDisplayContextParameter()).isEqualTo("#ARGUMENT(CREATE)");
    assertThat(flagLauncher.getCaseEventColumns()).containsEntry("PageColumnNumber", null);
  }
}
