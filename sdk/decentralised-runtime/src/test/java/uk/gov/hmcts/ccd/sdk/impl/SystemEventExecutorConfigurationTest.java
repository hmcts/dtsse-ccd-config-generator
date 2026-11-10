package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;

class SystemEventExecutorConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(SystemEventExecutorImpl.class)
      .withBean(CaseEventTransactionCoordinator.class, () -> mock(CaseEventTransactionCoordinator.class))
      .withBean(CaseDataRepository.class, () -> mock(CaseDataRepository.class))
      .withBean(IdamService.class, () -> mock(IdamService.class));

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

  @Test
  void configuredIdTakesPrecedenceOverPassword() {
    contextRunner
        .withPropertyValues(
            "ccd.decentralised-runtime.system-user.id=system-id",
            "ccd.decentralised-runtime.system-user.username=system-user",
            "ccd.decentralised-runtime.system-user.password=secret"
        )
        // Only a configured id requires names at startup; the IDAM lookup would default them. So this
        // failure shows the password was ignored in favour of the id.
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("System user first name is required"));
  }

  @Test
  void createsExecutorFromUsernameAndPasswordWithoutCallingIdamAtStartup() {
    contextRunner
        .withPropertyValues(
            "ccd.decentralised-runtime.system-user.username=system-user",
            "ccd.decentralised-runtime.system-user.password=secret"
        )
        .run(context -> {
          assertThat(context).hasSingleBean(SystemEventExecutor.class);
          verify(context.getBean(IdamService.class), never()).signIn(anyString(), anyString());
        });
  }

  @Test
  void treatsABlankIdAsNotConfigured() {
    contextRunner
        .withPropertyValues(
            "ccd.decentralised-runtime.system-user.id=",
            "ccd.decentralised-runtime.system-user.username=system-user",
            "ccd.decentralised-runtime.system-user.password=secret"
        )
        .run(context -> assertThat(context).hasSingleBean(SystemEventExecutor.class));
  }

  @Test
  void failsClearlyWhenPasswordIsConfiguredWithoutUsername() {
    contextRunner
        .withPropertyValues("ccd.decentralised-runtime.system-user.password=secret")
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("System username is required"));
  }

  @Test
  void treatsBlankPlaceholdersAsNotConfigured() {
    contextRunner
        .withPropertyValues(
            "ccd.decentralised-runtime.system-user.id=",
            "ccd.decentralised-runtime.system-user.password= "
        )
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(SystemEventExecutor.class);
        });
  }
}
