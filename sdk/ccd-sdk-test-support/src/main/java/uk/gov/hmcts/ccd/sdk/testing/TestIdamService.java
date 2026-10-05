package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import uk.gov.hmcts.ccd.sdk.impl.IdamService;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

/**
 * Resolves actor identities for event audit records from {@link TestActors}, without an external IDAM.
 */
final class TestIdamService extends IdamService {

  static final String DEFAULT_TOKEN = "Bearer ccd-sdk-test";
  private static final UserInfo DEFAULT_USER = new UserInfo(
      "ccd-sdk-test", "ccd-sdk-test", "SDK Test User", "SDK", "Test User", List.of("caseworker"));

  private final TestActors actors;

  TestIdamService(TestActors actors) {
    super(null, 1, 1);
    this.actors = actors;
  }

  @Override
  public User retrieveUser(String authorisation) {
    String token = TestActors.normalise(authorisation);
    if (DEFAULT_TOKEN.equals(token)) {
      return new User(token, DEFAULT_USER);
    }
    ActorDetails actor = actors.require(token);
    return new User(token, new UserInfo(actor.email(), actor.uid(),
        actor.givenName() + " " + actor.familyName(), actor.givenName(), actor.familyName(), actor.roles()));
  }
}
