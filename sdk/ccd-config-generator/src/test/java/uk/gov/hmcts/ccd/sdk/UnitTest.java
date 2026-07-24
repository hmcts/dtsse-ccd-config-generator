package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
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
}
