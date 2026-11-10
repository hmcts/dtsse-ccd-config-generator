package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.TokenResponse;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

class IdamServiceTest {

  private static final String USERNAME = "system@example.com";

  private final IdamClient mockIdamClient = mock(IdamClient.class);
  private final IdamService signInService = new IdamService(mockIdamClient, 10, 60);

  @Test
  void shouldCacheUserDetailsForBearerToken() {
    var userInfo = new UserInfo("sub", "uid", "name", "given", "family", java.util.List.of("caseworker"));
    var idamClient = new StubIdamClient(userInfo);
    var idamService = new IdamService(idamClient, 10, 60);

    var firstCall = idamService.retrieveUser("token");
    var secondCall = idamService.retrieveUser("token");

    assertThat(firstCall).isSameAs(secondCall);
    assertThat(firstCall.userDetails()).isEqualTo(userInfo);
    assertThat(idamClient.callCount).isEqualTo(1);
    assertThat(idamClient.lastToken).isEqualTo("Bearer token");
  }

  @Test
  void shouldNormaliseBearerPrefixCaseAndReuseCacheEntry() {
    var userInfo = new UserInfo("sub", "uid", "name", "given", "family", java.util.List.of("caseworker"));
    var idamClient = new StubIdamClient(userInfo);
    var idamService = new IdamService(idamClient, 10, 60);

    var firstCall = idamService.retrieveUser("bearer token");
    var secondCall = idamService.retrieveUser("BEARER token");

    assertThat(firstCall).isSameAs(secondCall);
    assertThat(idamClient.callCount).isEqualTo(1);
    assertThat(idamClient.lastToken).isEqualTo("Bearer token");
  }

  // signIn's success path runs end to end in the cftlib suite. These cover IDAM responses the
  // cftlib IDAM simulator cannot be made to return.
  @Test
  void signsInAndReturnsABearerTokenWithTheUser() {
    var userInfo = new UserInfo(USERNAME, "a1b2c3", USERNAME, "Idam", "User", List.of("caseworker"));
    when(mockIdamClient.getAccessTokenResponse(USERNAME, "secret")).thenReturn(token("abc"));
    when(mockIdamClient.getUserInfo("Bearer abc")).thenReturn(userInfo);

    var user = signInService.signIn(USERNAME, "secret");

    assertThat(user.authToken()).isEqualTo("Bearer abc");
    assertThat(user.userDetails()).isSameAs(userInfo);
  }

  @Test
  void rejectsAMissingAccessTokenWithoutCallingUserInfo() {
    when(mockIdamClient.getAccessTokenResponse(USERNAME, "secret")).thenReturn(token(null));

    assertThatThrownBy(() -> signInService.signIn(USERNAME, "secret"))
        .isInstanceOf(IdamService.InvalidSignInException.class)
        .hasMessage("IDAM returned an empty access token for the system user");
    verify(mockIdamClient, never()).getUserInfo(anyString());
  }

  @Test
  void rejectsAUserWithNoId() {
    when(mockIdamClient.getAccessTokenResponse(USERNAME, "secret")).thenReturn(token("abc"));
    when(mockIdamClient.getUserInfo("Bearer abc"))
        .thenReturn(new UserInfo(USERNAME, " ", USERNAME, "Idam", "User", List.of()));

    assertThatThrownBy(() -> signInService.signIn(USERNAME, "secret"))
        .isInstanceOf(IdamService.InvalidSignInException.class)
        .hasMessage("IDAM returned no user ID for the system user");
  }

  @Test
  void rejectsAnEmptyUserInfoResponse() {
    when(mockIdamClient.getAccessTokenResponse(USERNAME, "secret")).thenReturn(token("abc"));

    assertThatThrownBy(() -> signInService.signIn(USERNAME, "secret"))
        .isInstanceOf(IdamService.InvalidSignInException.class)
        .hasMessage("IDAM returned no user ID for the system user");
  }

  private static TokenResponse token(String accessToken) {
    return new TokenResponse(accessToken, "3600", null, null, "openid", "Bearer");
  }

  private static class StubIdamClient extends IdamClient {
    private int callCount;
    private String lastToken;
    private final UserInfo userInfo;

    StubIdamClient(UserInfo userInfo) {
      super(null, null);
      this.userInfo = userInfo;
    }

    @Override
    public UserInfo getUserInfo(String authorisation) {
      callCount++;
      lastToken = authorisation;
      return userInfo;
    }
  }
}
