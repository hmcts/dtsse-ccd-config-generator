package uk.gov.hmcts.ccd.sdk.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackBridge;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

class JsonEventReplacementIntegrationTest {

  @TempDir
  Path definitions;

  @ParameterizedTest
  @CsvSource({
      "BOTH, BOTH",
      "NONE, BOTH"
  })
  void allowsRetainedAndAdditionalCallbackPhases(Callbacks json, Callbacks java) throws IOException {
    context(json, java).run(context -> {
      assertThat(context).hasNotFailed();
      ResolvedCCDConfig<?, ?, ?> resolved = context.getBean(ResolvedCCDConfig.class);
      var event = resolved.getEvents().get("update");
      assertThat(event.getName()).isEqualTo("Java replacement");
      if (java.aboutToSubmit) {
        assertThat(event.getAboutToSubmitCallback().handle(null, null).getData()).isInstanceOf(CaseData.class);
      }
      if (java.submitted) {
        assertThat(event.getSubmittedCallback().handle(null, null).getConfirmationHeader()).isEqualTo("Java");
      }
    });
  }

  @ParameterizedTest
  @CsvSource({
      "BOTH, ABOUT_TO_SUBMIT, submitted",
      "BOTH, SUBMITTED, about-to-submit",
      "BOTH, NONE, 'about-to-submit, submitted'"
  })
  void failsStartupWhenReplacementDropsCallbacks(Callbacks json, Callbacks java, String missing)
      throws IOException {
    context(json, java).run(context -> assertThat(context.getStartupFailure())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("Replacement for event 'update' in case type 'TEST' drops callbacks: " + missing));
  }

  @Test
  void allowsDecentralisedReplacementWhileJsonRetainsCallbackUrls() throws IOException {
    context(Callbacks.BOTH, null)
        .withBean(DecentralisedConfig.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          ResolvedCCDConfig<?, ?, ?> resolved = context.getBean(ResolvedCCDConfig.class);
          var event = resolved.getEvents().get("update");
          assertThat(event.getAboutToSubmitCallback()).isNull();
          assertThat(event.getSubmittedCallback()).isNull();
          assertThat(event.getSubmitHandler().submit(null).getConfirmationHeader()).isEqualTo("Decentralised");
        });
  }

  @Test
  void keepsJsonCallbacksWhenEventIsNotReplaced() throws IOException {
    context(Callbacks.BOTH, null).run(context -> {
      assertThat(context).hasNotFailed();
      ResolvedCCDConfig<?, ?, ?> resolved = context.getBean(ResolvedCCDConfig.class);
      var event = resolved.getEvents().get("update");
      assertThat(event.getAboutToSubmitCallback()).isNotNull();
      assertThat(event.getSubmittedCallback()).isNotNull();
    });
  }

  @ParameterizedTest
  @CsvSource({
      "ABOUT_TO_SUBMIT, submitted",
      "SUBMITTED, about-to-submit",
      "NONE, 'about-to-submit, submitted'"
  })
  void rejectsLostCallbacksAfterDecentralisedReplacement(Callbacks callbacks, String missing) throws IOException {
    context(Callbacks.BOTH, null)
        .withBean(ReplacementChainConfig.class, () -> new ReplacementChainConfig(callbacks))
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Replacement for event 'update' in case type 'TEST' drops callbacks: " + missing));
  }

  @ParameterizedTest
  @CsvSource({"BOTH, BOTH", "NONE, NONE"})
  void allowsCallbackReplacementThatMeetsEarlierRequirements(Callbacks json, Callbacks callbacks) throws IOException {
    context(json, null)
        .withBean(ReplacementChainConfig.class, () -> new ReplacementChainConfig(callbacks))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ResolvedCCDConfig<?, ?, ?> resolved = context.getBean(ResolvedCCDConfig.class);
          var event = resolved.getEvents().get("update");
          assertThat(event.getSubmitHandler()).isNull();
          assertThat(event.getAboutToSubmitCallback() != null).isEqualTo(callbacks.aboutToSubmit);
          assertThat(event.getSubmittedCallback() != null).isEqualTo(callbacks.submitted);
        });
  }

  private ApplicationContextRunner context(Callbacks json, Callbacks java) throws IOException {
    Files.writeString(definitions.resolve("CaseType.json"), """
        [{"ID": "TEST", "JurisdictionID": "TEST"}]
        """);
    Files.writeString(definitions.resolve("CaseEvent.json"), """
        [{"ID": "update", "CaseTypeID": "TEST", "Name": "JSON event",
          "CallBackURLAboutToSubmitEvent": "%s", "CallBackURLSubmittedEvent": "%s"}]
        """.formatted(
            json.aboutToSubmit ? "https://callbacks.example/about-to-submit" : "",
            json.submitted ? "https://callbacks.example/submitted" : " "
        ));
    var runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class, JsonCCDConfigSupport.class, JsonCallbackBridge.class)
        .withBean(CcdCaseDataMapperConfiguration.CCD_CASE_DATA_OBJECT_MAPPER, ObjectMapper.class, ObjectMapper::new)
        .withBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
        .withPropertyValues("definitions=" + definitions.toUri());
    return java == null ? runner : runner.withBean("javaConfig", JavaConfig.class, () -> new JavaConfig(java));
  }

  @Configuration(proxyBeanMethods = false)
  static class TestConfiguration {
    @Bean
    CCDConfig<CaseData, State, Role> jsonConfig(JsonCCDConfigSupport support, @Value("${definitions}") String root) {
      return new JsonBackedCCDConfig<CaseData, State, Role>(support, "TEST", root) {};
    }

    @Bean
    ResolvedCCDConfig<?, ?, ?> resolvedConfig(List<CCDConfig<?, ?, ?>> configs) {
      return new CCDDefinitionGenerator(configs, null).loadConfigs().getFirst();
    }
  }

  record JavaConfig(Callbacks callbacks) implements CCDConfig<CaseData, State, Role> {
    @Override
    public Set<String> caseTypeIds() {
      return Set.of("TEST");
    }

    @Override
    public void configure(ConfigBuilder<CaseData, State, Role> builder) {
      var event = builder.event("update").forAllStates().name("Java replacement");
      if (callbacks.aboutToSubmit) {
        event.aboutToSubmitCallback((details, before) -> AboutToStartOrSubmitResponse.<CaseData, State>builder()
            .data(new CaseData()).build());
      }
      if (callbacks.submitted) {
        event.submittedCallback((details, before) -> SubmittedCallbackResponse.builder()
            .confirmationHeader("Java").build());
      }
    }
  }

  static class DecentralisedConfig implements CCDConfig<CaseData, State, Role> {
    @Override
    public Set<String> caseTypeIds() {
      return Set.of("TEST");
    }

    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<CaseData, State, Role> builder) {
      builder.decentralisedEvent("update", payload -> SubmitResponse.<State>builder()
          .confirmationHeader("Decentralised").build()).forAllStates();
    }
  }

  record ReplacementChainConfig(Callbacks callbacks) implements CCDConfig<CaseData, State, Role> {
    @Override
    public Set<String> caseTypeIds() {
      return Set.of("TEST");
    }

    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<CaseData, State, Role> builder) {
      new DecentralisedConfig().configureDecentralised(builder);
      new JavaConfig(callbacks).configure(builder);
    }
  }

  enum Callbacks {
    NONE(false, false), ABOUT_TO_SUBMIT(true, false), SUBMITTED(false, true), BOTH(true, true);

    final boolean aboutToSubmit;
    final boolean submitted;

    Callbacks(boolean aboutToSubmit, boolean submitted) {
      this.aboutToSubmit = aboutToSubmit;
      this.submitted = submitted;
    }
  }

  static class CaseData {}

  enum State { OPEN }

  enum Role implements HasRole {
    USER;

    @Override
    public String getRole() {
      return "user";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
