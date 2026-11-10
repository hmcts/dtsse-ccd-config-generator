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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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

    assertThatThrownBy(resolver::get)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageContaining("will not be retried until restart")
        .hasCauseInstanceOf(FeignException.Unauthorized.class);
    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER.multipliedBy(10));

    // The remembered failure is a description, not the Feign exception that holds the token request.
    assertThatThrownBy(resolver::get)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageContaining("will not be retried until restart")
        .hasNoCause();
    verify(idamService, times(1)).signIn(USERNAME, "secret");
  }

  @Test
  void neverRetriesASignInThatReturnedAnUnusableUser() {
    when(idamService.signIn(USERNAME, "secret"))
        .thenThrow(new IdamService.InvalidSignInException("IDAM returned no user ID for the system user"));
    var resolver = resolver("", "", ACCEPT_ANY);

    assertThatThrownBy(resolver::get).hasMessageContaining("will not be retried until restart");
    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER.multipliedBy(10));

    assertThatThrownBy(resolver::get).hasMessageContaining("IDAM returned no user ID for the system user");
    verify(idamService, times(1)).signIn(USERNAME, "secret");
  }

  @Test
  void neverRetriesAnIdentityThatFailsValidation() {
    when(idamService.signIn(USERNAME, "secret")).thenReturn(SIGNED_IN);
    var resolver = resolver("", "", identity -> {
      throw new IllegalArgumentException("System user first name is required");
    });

    assertThatThrownBy(resolver::get)
        .hasMessageContaining("System user first name is required")
        .hasMessageContaining("will not be retried until restart");
    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER.multipliedBy(10));

    assertThatThrownBy(resolver::get).hasMessageContaining("System user first name is required");
    verify(idamService, times(1)).signIn(USERNAME, "secret");
  }

  @Test
  void failsFastWhileIdamIsUnavailableThenRetriesAfterTheRetryPeriod() {
    when(idamService.signIn(USERNAME, "secret"))
        .thenThrow(feignStatus(503))
        .thenReturn(SIGNED_IN);
    var resolver = resolver("", "", ACCEPT_ANY);

    assertThatThrownBy(resolver::get)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 503")
        .hasCauseInstanceOf(FeignException.ServiceUnavailable.class);
    assertThatThrownBy(resolver::get)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("the next attempt is after");
    verify(idamService, times(1)).signIn(USERNAME, "secret");

    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER);

    assertThat(resolver.get().id()).isEqualTo("a1b2c3");
    verify(idamService, times(2)).signIn(USERNAME, "secret");
  }

  @Test
  void retriesAnUnexpectedIllegalStateFromTheHttpStack() {
    when(idamService.signIn(USERNAME, "secret"))
        .thenThrow(new IllegalStateException("Connection pool shut down"))
        .thenReturn(SIGNED_IN);
    var resolver = resolver("", "", ACCEPT_ANY);

    assertThatThrownBy(resolver::get).hasMessageContaining("the next attempt is after");
    clock.advanceBy(SystemIdentityResolver.RETRY_AFTER);

    assertThat(resolver.get().id()).isEqualTo("a1b2c3");
  }

  @Test
  void waitsForALookupAlreadyInProgress() throws Exception {
    var lookupStarted = new CountDownLatch(1);
    var releaseLookup = new CountDownLatch(1);
    when(idamService.signIn(USERNAME, "secret")).thenAnswer(invocation -> {
      lookupStarted.countDown();
      releaseLookup.await();
      return SIGNED_IN;
    });
    var resolver = resolver("", "", ACCEPT_ANY, Duration.ofSeconds(10));
    var executor = Executors.newSingleThreadExecutor();
    var second = new FutureTask<>(resolver::get);
    var secondCaller = new Thread(second);
    try {
      final Future<SystemIdentity> first = executor.submit(resolver::get);
      assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();
      secondCaller.start();
      awaitWaiting(secondCaller);
      releaseLookup.countDown();

      assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(first.get(5, TimeUnit.SECONDS));
      verify(idamService, times(1)).signIn(USERNAME, "secret");
    } finally {
      releaseLookup.countDown();
      executor.shutdownNow();
      secondCaller.join(5_000);
    }
  }

  /** Waits until the thread is parked, which for the second caller means waiting on the lookup. */
  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != Thread.State.TIMED_WAITING) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Second caller never waited on the lookup; state " + thread.getState());
      }
      Thread.sleep(10);
    }
  }

  @Test
  void failsRatherThanQueueBehindASlowLookup() throws Exception {
    var lookupStarted = new CountDownLatch(1);
    var releaseLookup = new CountDownLatch(1);
    when(idamService.signIn(USERNAME, "secret")).thenAnswer(invocation -> {
      lookupStarted.countDown();
      releaseLookup.await();
      return SIGNED_IN;
    });
    var resolver = resolver("", "", ACCEPT_ANY, Duration.ofMillis(50));
    var executor = Executors.newSingleThreadExecutor();
    try {
      final Future<SystemIdentity> first = executor.submit(resolver::get);
      assertThat(lookupStarted.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(resolver::get)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("The system user is still being looked up from IDAM");

      releaseLookup.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).id()).isEqualTo("a1b2c3");
      verify(idamService, times(1)).signIn(USERNAME, "secret");
    } finally {
      releaseLookup.countDown();
      executor.shutdownNow();
    }
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
    return resolver(firstNameOverride, lastNameOverride, validator, SystemIdentityResolver.LOOKUP_WAIT);
  }

  private SystemIdentityResolver resolver(
      String firstNameOverride,
      String lastNameOverride,
      Consumer<SystemIdentity> validator,
      Duration lookupWait
  ) {
    return SystemIdentityResolver.fromIdam(
        idamService, USERNAME, "secret", firstNameOverride, lastNameOverride, validator, clock, lookupWait
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
