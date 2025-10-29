package uk.gov.hmcts.ccd.sdk;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.idam.client.IdamClient;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

import java.time.Duration;

@Service("CcdSdkIdamService")
class IdamService {

  private static final String BEARER_PREFIX = "Bearer" + " ";

  private final IdamClient idamClient;
  private final Cache<String, User> userCache;

  IdamService(
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

  private String getBearerToken(String token) {
    if (token == null || token.isBlank()) {
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
