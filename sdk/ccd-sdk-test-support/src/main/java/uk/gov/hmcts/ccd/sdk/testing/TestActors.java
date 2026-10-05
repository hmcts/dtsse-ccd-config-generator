package uk.gov.hmcts.ccd.sdk.testing;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The actors registered with test support, keyed by the bearer token each one sends.
 *
 * <p>An application that authenticates callers through its own IDAM client can provide a test
 * bean for that client which answers from this registry, so every actor a test registers is known
 * to the application without per-test stubbing.
 */
public final class TestActors {

  private final Map<String, ActorDetails> actors = new ConcurrentHashMap<>();

  TestActors() {
  }

  String register(ActorDetails actor) {
    String authorisation = "Bearer ccd-sdk-test-" + UUID.randomUUID();
    actors.put(authorisation, actor);
    return authorisation;
  }

  /**
   * Finds the actor sending this token, with or without its {@code Bearer } prefix.
   */
  public Optional<ActorDetails> lookup(String authorisation) {
    return Optional.ofNullable(actors.get(normalise(authorisation)));
  }

  /**
   * The actor sending this token, or an {@link IllegalArgumentException} naming the token.
   */
  public ActorDetails require(String authorisation) {
    return lookup(authorisation).orElseThrow(() ->
        new IllegalArgumentException("No SDK test actor registered for token " + normalise(authorisation)));
  }

  static String normalise(String token) {
    return token.startsWith("Bearer ") ? token : "Bearer " + token;
  }
}
