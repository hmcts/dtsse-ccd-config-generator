package uk.gov.hmcts.reform;

import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

@Configuration
public class MultiCaseTypeConfig {

  @Bean
  CCDConfig<CaseData, State, UserRole> firstCaseType() {
    return new CCDConfig<>() {
      @Override
      public Set<String> caseTypeIds() {
        return Set.of("MULTI_CASE_TYPE_ONE");
      }

      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.caseType("MULTI_CASE_TYPE_ONE", "First case type", "First case type");
        builder.event("first-event").forAllStates().name("First event");
      }
    };
  }

  @Bean
  CCDConfig<CaseData, State, UserRole> secondCaseType() {
    return new CCDConfig<>() {
      @Override
      public Set<String> caseTypeIds() {
        return Set.of("MULTI_CASE_TYPE_TWO");
      }

      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.caseType("MULTI_CASE_TYPE_TWO", "Second case type", "Second case type");
        builder.event("second-event").forAllStates().name("Second event");
      }
    };
  }

  @Bean
  CCDConfig<CaseData, State, UserRole> sharedCaseTypeConfig() {
    return new CCDConfig<>() {
      @Override
      public Set<String> caseTypeIds() {
        return Set.of("MULTI_CASE_TYPE_ONE", "MULTI_CASE_TYPE_TWO");
      }

      @Override
      public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        builder.event("shared-event").forAllStates().name("Shared event");
      }
    };
  }

  static class CaseData {
  }
}
