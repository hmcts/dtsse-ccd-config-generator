package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;
import uk.gov.hmcts.reform.idam.client.IdamClient;

class SystemEventExecutorConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(SystemEventExecutorImpl.class)
      .withBean(CaseEventTransactionCoordinator.class, () -> mock(CaseEventTransactionCoordinator.class))
      .withBean(CaseDataRepository.class, () -> mock(CaseDataRepository.class));

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
  void createsExecutorFromUsernameAndPasswordWithoutCallingIdamAtStartup() {
    IdamClient idamClient = mock(IdamClient.class);
    contextRunner
        .withBean(IdamClient.class, () -> idamClient)
        .withPropertyValues(
            "ccd.decentralised-runtime.system-user.username=system-user",
            "ccd.decentralised-runtime.system-user.password=secret"
        )
        .run(context -> {
          assertThat(context).hasSingleBean(SystemEventExecutor.class);
          verify(idamClient, never()).getAccessToken(anyString(), anyString());
          verify(idamClient, never()).getUserInfo(any());
        });
  }

  @Test
  void treatsABlankIdAsNotConfigured() {
    contextRunner
        .withBean(IdamClient.class, () -> mock(IdamClient.class))
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
        .withBean(IdamClient.class, () -> mock(IdamClient.class))
        .withPropertyValues("ccd.decentralised-runtime.system-user.password=secret")
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("System username is required"));
  }

  @Test
  void failsClearlyWhenIdamLookupIsConfiguredWithoutAnIdamClient() {
    contextRunner
        .withPropertyValues(
            "ccd.decentralised-runtime.system-user.username=system-user",
            "ccd.decentralised-runtime.system-user.password=secret"
        )
        .run(context -> assertThat(context.getStartupFailure())
            .rootCause()
            .hasMessageContaining("An IdamClient bean is required"));
  }
}
