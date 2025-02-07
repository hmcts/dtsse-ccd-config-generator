package uk.gov.hmcts.ccd.sdk;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

public class IdamService {
    @Autowired
    private IdamClient idamClient;
    private static final String BEARER_PREFIX = "Bearer" + " ";


    public User retrieveUser(String authorisation) {
        final String bearerToken = getBearerToken(authorisation);
        final var userDetails = idamClient.getUserInfo(bearerToken);

        return new User(bearerToken, userDetails);
    }

    private String getCachedIdamOauth2Token(String username, String password) {
      return idamClient.getAccessToken(username, password);
    }

    private String getBearerToken(String token) {
        if (token.isBlank()) {
            return token;
        }
        return token.startsWith(BEARER_PREFIX) ? token : BEARER_PREFIX.concat(token);
    }

  @Getter
  @AllArgsConstructor
  public static class User {
    private String authToken;
    private UserInfo userDetails;
  }

}
