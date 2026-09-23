package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import uk.gov.hmcts.ccd.sdk.impl.IdamService;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

/** Local actor identities for event audit records, without an external IDAM service. */
final class TestIdamService extends IdamService {

  static final String DEFAULT_TOKEN = "Bearer ccd-sdk-test";

  private final Map<String, UserInfo> users = new ConcurrentHashMap<>();

  public TestIdamService() {
    super(null, 1, 1);
    users.put(DEFAULT_TOKEN, new UserInfo(
        "ccd-sdk-test", "ccd-sdk-test", "SDK Test User", "SDK", "Test User", List.of("caseworker")
    ));
  }

  String register(String token, ActorDetails actor) {
    String authorisation = normalise(token);
    users.put(authorisation, new UserInfo(actor.email(), actor.uid(),
        actor.givenName() + " " + actor.familyName(), actor.givenName(), actor.familyName(), actor.roles()));
    return authorisation;
  }

  @Override
  public User retrieveUser(String authorisation) {
    String token = normalise(authorisation);
    UserInfo user = users.get(token);
    if (user == null) {
      throw new IllegalArgumentException("No SDK test actor registered for token " + token);
    }
    return new User(token, user);
  }

  private String normalise(String token) {
    return token.startsWith("Bearer ") ? token : "Bearer " + token;
  }
}
