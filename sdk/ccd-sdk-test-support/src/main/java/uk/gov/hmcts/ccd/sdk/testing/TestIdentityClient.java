package uk.gov.hmcts.ccd.sdk.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Client;
import feign.Request;
import feign.Response;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Answers the platform identity calls an application makes while handling an event, from the
 * actors test support registers, and passes every other request to the application's own client:
 * IDAM user info for the caller's token, an S2S lease, and role assignments (none).
 */
final class TestIdentityClient implements Client {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ROLE_ASSIGNMENTS = Pattern.compile(".*/am/role-assignments/actors/[^/]+");

  private final Client delegate;
  private final TestActors actors;

  TestIdentityClient(Client delegate, TestActors actors) {
    this.delegate = delegate;
    this.actors = actors;
  }

  @Override
  public Response execute(Request request, Request.Options options) throws IOException {
    String path = URI.create(request.url()).getPath();
    boolean get = request.httpMethod() == Request.HttpMethod.GET;
    if (get && path.endsWith("/o/userinfo")) {
      return userInfo(request);
    }
    if (request.httpMethod() == Request.HttpMethod.POST && path.endsWith("/lease")) {
      return respond(request, 200, "text/plain", serviceToken());
    }
    if (get && ROLE_ASSIGNMENTS.matcher(path).matches()) {
      return respond(request, 200, "application/json", "{\"roleAssignmentResponse\":[]}");
    }
    return delegate.execute(request, options);
  }

  private Response userInfo(Request request) throws JsonProcessingException {
    String authorisation = header(request, "Authorization");
    if (authorisation != null && TestActors.normalise(authorisation).equals(TestIdamService.DEFAULT_TOKEN)) {
      return respond(request, 200, "application/json", JSON.writeValueAsString(Map.of(
          "sub", "ccd-sdk-test", "uid", "ccd-sdk-test", "name", "SDK Test User",
          "given_name", "SDK", "family_name", "Test User", "roles", List.of("caseworker"))));
    }
    Optional<ActorDetails> actor = authorisation == null ? Optional.empty() : actors.lookup(authorisation);
    if (actor.isEmpty()) {
      return respond(request, 401, "application/json", "{\"error\":\"invalid_token\"}");
    }
    ActorDetails details = actor.get();
    return respond(request, 200, "application/json", JSON.writeValueAsString(Map.of(
        "sub", details.email(),
        "uid", details.uid(),
        "name", details.givenName() + " " + details.familyName(),
        "given_name", details.givenName(),
        "family_name", details.familyName(),
        "roles", details.roles())));
  }

  /** A well-formed, unsigned-looking JWT that S2S clients can decode for its expiry. */
  private static String serviceToken() {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    long expiry = Instant.now().plus(1, ChronoUnit.DAYS).getEpochSecond();
    return encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
        + "." + encoder.encodeToString(("{\"sub\":\"ccd-sdk-test\",\"exp\":" + expiry + "}")
            .getBytes(StandardCharsets.UTF_8))
        + "." + encoder.encodeToString("ccd-sdk-test".getBytes(StandardCharsets.UTF_8));
  }

  private static String header(Request request, String name) {
    return request.headers().entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .flatMap(Collection::stream)
        .findFirst()
        .orElse(null);
  }

  private static Response respond(Request request, int status, String contentType, String body) {
    return Response.builder()
        .request(request)
        .status(status)
        .reason(status == 200 ? "OK" : "Unauthorized")
        .headers(Map.of("Content-Type", List.of(contentType)))
        .body(body, StandardCharsets.UTF_8)
        .build();
  }
}
