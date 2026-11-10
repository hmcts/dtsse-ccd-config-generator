package uk.gov.hmcts.ccd.sdk.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalUser;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

@Service("CcdSdkIdamService")
public class IdamService {

  private static final String BEARER_PREFIX = "Bearer ";

  private final IdamClient idamClient;
  private final Cache<String, User> userCache;

  public IdamService(
      IdamClient idamClient,
      @Value("${ccd.decentralised-runtime.idam-cache.max-size:500}") long cacheMaxSize,
      @Value("${ccd.decentralised-runtime.idam-cache.ttl-seconds:120}") long cacheTtlSeconds) {
    this.idamClient = idamClient;
    this.userCache = Caffeine.newBuilder()
        .maximumSize(cacheMaxSize)
        .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds))
        .build();
  }

  public User retrieveUser(String authorisation) {
    return userCache.get(
        getBearerToken(authorisation),
        token -> new User(token, idamClient.getUserInfo(token))
    );
  }

  /**
   * Signs a service-owned user, such as a system user, in to IDAM with a password grant.
   *
   * @throws IllegalStateException if IDAM returns no token or a user without an ID
   */
  public User signIn(String username, String password) {
    String token = idamClient.getAccessToken(username, password);
    if (!StringUtils.hasText(token)) {
      throw new IllegalStateException("IDAM returned an empty access token for the system user");
    }
    UserInfo userInfo = idamClient.getUserInfo(token);
    if (userInfo == null || !StringUtils.hasText(userInfo.getUid())) {
      throw new IllegalStateException("IDAM returned no user ID for the system user");
    }
    return new User(token, userInfo);
  }

  private String getBearerToken(String token) {
    if (token == null || token.isBlank()) {
      return token;
    }
    if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return BEARER_PREFIX.concat(token.substring(BEARER_PREFIX.length()));
    }
    return BEARER_PREFIX.concat(token);
  }

  public record User(String authToken, UserInfo userDetails) {

    /** This user as an external event's handler is given them. */
    public ExternalUser toExternalUser() {
      return new ExternalUser(userDetails.getUid(), userDetails.getRoles(), authToken);
    }
  }
}
