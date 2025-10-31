package uk.gov.hmcts.ccd.sdk.impl;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

import static org.assertj.core.api.Assertions.assertThat;

class IdamServiceTest {

  @Test
  void shouldCacheUserDetailsForBearerToken() {
    var userInfo = new UserInfo("sub", "uid", "name", "given", "family", java.util.List.of("caseworker"));
    var idamClient = new StubIdamClient(userInfo);
    var idamService = new IdamService(idamClient, 10, 60);

    var firstCall = idamService.retrieveUser("token");
    var secondCall = idamService.retrieveUser("token");

    assertThat(firstCall).isSameAs(secondCall);
    assertThat(firstCall.getUserDetails()).isEqualTo(userInfo);
    assertThat(idamClient.callCount).isEqualTo(1);
    assertThat(idamClient.lastToken).isEqualTo("Bearer token");
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
