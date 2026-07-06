package uk.gov.hmcts.ccd.sdk.retention.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import uk.gov.hmcts.ccd.sdk.retention.RetentionProperties;
import uk.gov.hmcts.reform.idam.client.IdamClient;

public class RetentionSystemUserTokenProvider {
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CACHE_KEY = "retention-system-user";

  private final IdamClient idamClient;
  private final RetentionProperties properties;
  private final Cache<String, SystemUser> cache;

  public RetentionSystemUserTokenProvider(IdamClient idamClient, RetentionProperties properties) {
    this.idamClient = idamClient;
    this.properties = properties;
    this.cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(30))
        .maximumSize(1)
        .build();
  }

  public SystemUser systemUser() {
    return cache.get(CACHE_KEY, ignored -> loadSystemUser());
  }

  private SystemUser loadSystemUser() {
    String username = properties.getSystemUser().getUsername();
    String password = properties.getSystemUser().getPassword();
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalStateException(
          "Retention system user credentials must be configured with "
              + "ccd.decentralised-runtime.retention.system-user.username and password"
      );
    }

    String authorization = bearer(idamClient.getAccessToken(username, password));
    String uid = idamClient.getUserInfo(authorization).getUid();
    return new SystemUser(authorization, uid);
  }

  private String bearer(String token) {
    if (token == null || token.isBlank()) {
      return token;
    }
    if (token.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return BEARER_PREFIX.concat(token.substring(BEARER_PREFIX.length()));
    }
    return BEARER_PREFIX.concat(token);
  }

  public record SystemUser(String authorization, String uid) {
  }
}
