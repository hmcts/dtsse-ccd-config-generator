package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.impl.SystemIdentityResolver.SystemIdentity;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

class SystemIdentityResolverTest {

  private static final String USERNAME = "system@example.com";
  private static final IdamService.User SIGNED_IN = new IdamService.User(
      "Bearer token",
      new UserInfo(USERNAME, "a1b2c3", USERNAME, "Idam", "User", List.of("caseworker"))
  );
  private static final Consumer<SystemIdentity> ACCEPT_ANY = identity -> { };

  private final IdamService idamService = mock(IdamService.class);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));

  @Test
  void looksUpTheSystemUserFromIdamOnceAndCachesIt() {
    when(idamService.signIn(USERNAME, "secret")).thenReturn(SIGNED_IN);
    var resolver = resolver("", "", ACCEPT_ANY);

    SystemIdentity first = resolver.get();
    SystemIdentity second = resolver.get();

    assertThat(first).isEqualTo(new SystemIdentity("a1b2c3", USERNAME, "Idam", "User"));
    assertThat(second).isSameAs(first);
    verify(idamService, times(1)).signIn(USERNAME, "secret");
  }

  @Test
  void configuredNamesOverrideTheIdamNames() {
    when(idamService.signIn(USERNAME, "secret")).thenReturn(SIGNED_IN);
    var resolver = resolver("Employment Tribunals", "System", ACCEPT_ANY);

    assertThat(resolver.get())
        .isEqualTo(new SystemIdentity("a1b2c3", USERNAME, "Employment Tribunals", "System"));
  }

  @Test
  void neverRetriesASignInThatIdamRejected() {
    when(idamService.signIn(USERNAME, "secret")).thenThrow(feignStatus(401));
    var resolver = resolver("", "", ACCEPT_ANY);

    assertThatThrownBy(resolver::get).isInstanceOf(FeignException.Unauthorized.class);
    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER.multipliedBy(10));

    assertThatThrownBy(resolver::get)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("will not be retried until restart")
        .hasCauseInstanceOf(FeignException.Unauthorized.class);
    verify(idamService, times(1)).signIn(USERNAME, "secret");
  }

  @Test
  void neverRetriesAnIdentityThatFailsValidation() {
    when(idamService.signIn(USERNAME, "secret")).thenReturn(SIGNED_IN);
    var resolver = resolver("", "", identity -> {
      throw new IllegalArgumentException("System user first name is required");
    });

    assertThatThrownBy(resolver::get).hasMessage("System user first name is required");
    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER.multipliedBy(10));

    assertThatThrownBy(resolver::get).hasRootCauseMessage("System user first name is required");
    verify(idamService, times(1)).signIn(USERNAME, "secret");
  }

  @Test
  void failsFastWhileIdamIsUnavailableThenRetriesAfterTheRetryPeriod() {
    when(idamService.signIn(USERNAME, "secret"))
        .thenThrow(feignStatus(503))
        .thenReturn(SIGNED_IN);
    var resolver = resolver("", "", ACCEPT_ANY);

    assertThatThrownBy(resolver::get).isInstanceOf(FeignException.ServiceUnavailable.class);
    assertThatThrownBy(resolver::get)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("the next attempt is after");
    verify(idamService, times(1)).signIn(USERNAME, "secret");

    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER);

    assertThat(resolver.get().id()).isEqualTo("a1b2c3");
    verify(idamService, times(2)).signIn(USERNAME, "secret");
  }

  @Test
  void fixedIdentityNeverCallsIdam() {
    var identity = new SystemIdentity("fixed-id", "system", "Case", "System");

    assertThat(SystemIdentityResolver.fixed(identity).get()).isSameAs(identity);
    verifyNoInteractions(idamService);
  }

  private SystemIdentityResolver resolver(
      String firstNameOverride,
      String lastNameOverride,
      Consumer<SystemIdentity> validator
  ) {
    return SystemIdentityResolver.fromIdam(
        idamService, USERNAME, "secret", firstNameOverride, lastNameOverride, validator, clock
    );
  }

  private static FeignException feignStatus(int status) {
    Request request = Request.create(
        Request.HttpMethod.POST, "/o/token", Map.of(), null, StandardCharsets.UTF_8, null
    );
    return FeignException.errorStatus(
        "IdamApi#generateOpenIdToken",
        Response.builder().status(status).reason("IDAM").request(request).headers(Map.of()).build()
    );
  }

  private static final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advanceBy(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
