package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.impl.SystemIdentityResolver.SystemIdentity;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

class SystemIdentityResolverTest {

  private static final UserInfo SYSTEM_USER_INFO = new UserInfo(
      "system@example.com", "a1b2c3", "system@example.com", "Idam", "User", List.of("caseworker")
  );

  private final IdamClient idamClient = mock(IdamClient.class);

  @Test
  void looksUpTheSystemUserFromIdamOnceAndCachesIt() {
    when(idamClient.getAccessToken("system@example.com", "secret")).thenReturn("Bearer token");
    when(idamClient.getUserInfo("Bearer token")).thenReturn(SYSTEM_USER_INFO);
    var resolver = SystemIdentityResolver.fromIdam(idamClient, "system@example.com", "secret", "", "");

    SystemIdentity first = resolver.get(identity -> { });
    SystemIdentity second = resolver.get(identity -> { });

    assertThat(first).isEqualTo(new SystemIdentity("a1b2c3", "system@example.com", "Idam", "User"));
    assertThat(second).isSameAs(first);
    verify(idamClient, times(1)).getAccessToken("system@example.com", "secret");
  }

  @Test
  void configuredNamesOverrideTheIdamNames() {
    when(idamClient.getAccessToken("system@example.com", "secret")).thenReturn("Bearer token");
    when(idamClient.getUserInfo("Bearer token")).thenReturn(SYSTEM_USER_INFO);
    var resolver = SystemIdentityResolver.fromIdam(
        idamClient, "system@example.com", "secret", "Employment Tribunals", "System"
    );

    assertThat(resolver.get(identity -> { }))
        .isEqualTo(new SystemIdentity("a1b2c3", "system@example.com", "Employment Tribunals", "System"));
  }

  @Test
  void retriesTheLookupAfterAFailure() {
    when(idamClient.getAccessToken("system@example.com", "secret"))
        .thenThrow(new IllegalStateException("IDAM unavailable"))
        .thenReturn("Bearer token");
    when(idamClient.getUserInfo("Bearer token")).thenReturn(SYSTEM_USER_INFO);
    var resolver = SystemIdentityResolver.fromIdam(idamClient, "system@example.com", "secret", "", "");

    assertThatThrownBy(() -> resolver.get(identity -> { })).hasMessage("IDAM unavailable");
    assertThat(resolver.get(identity -> { }).id()).isEqualTo("a1b2c3");
  }

  @Test
  void doesNotCacheAnIdentityThatFailsValidation() {
    when(idamClient.getAccessToken("system@example.com", "secret")).thenReturn("Bearer token");
    when(idamClient.getUserInfo("Bearer token")).thenReturn(SYSTEM_USER_INFO);
    var resolver = SystemIdentityResolver.fromIdam(idamClient, "system@example.com", "secret", "", "");

    assertThatThrownBy(() -> resolver.get(identity -> {
      throw new IllegalArgumentException("System user first name is required");
    })).hasMessage("System user first name is required");
    assertThat(resolver.get(identity -> { }).id()).isEqualTo("a1b2c3");
    verify(idamClient, times(2)).getAccessToken("system@example.com", "secret");
  }

  @Test
  void fixedIdentityNeverCallsIdam() {
    var identity = new SystemIdentity("fixed-id", "system", "Case", "System");

    assertThat(SystemIdentityResolver.fixed(identity).get(value -> { })).isSameAs(identity);
  }
}
