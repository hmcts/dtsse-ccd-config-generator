package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;

class SystemEventExecutorConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(SystemEventExecutorImpl.class)
      .withBean(CaseEventTransactionCoordinator.class, () -> mock(CaseEventTransactionCoordinator.class))
      .withBean(CaseDataRepository.class, () -> mock(CaseDataRepository.class))
      .withBean(ResolvedConfigRegistry.class, () -> mock(ResolvedConfigRegistry.class));

  @Test
  void doesNotCreateExecutorWithoutSystemUserConfiguration() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(SystemEventExecutor.class));
  }

  @Test
  void createsExecutorWithCompleteSystemUserConfiguration() {
    contextRunner
        .withPropertyValues(
            "ccd.decentralised-runtime.system-user.id=system-id",
            "ccd.decentralised-runtime.system-user.username=system-user",
            "ccd.decentralised-runtime.system-user.first-name=Case",
            "ccd.decentralised-runtime.system-user.last-name=System"
        )
        .run(context -> assertThat(context).hasSingleBean(SystemEventExecutor.class));
  }

  @Test
  void failsClearlyWhenSystemUserConfigurationIsIncomplete() {
    contextRunner
        .withPropertyValues("ccd.decentralised-runtime.system-user.id=system-id")
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("System username is required"));
  }
}
