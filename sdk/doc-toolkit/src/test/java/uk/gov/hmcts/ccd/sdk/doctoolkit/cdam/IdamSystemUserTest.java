package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.idam.client.IdamClient;

class IdamSystemUserTest {
  private final AtomicInteger signIns = new AtomicInteger();
  private final IdamClient idam = new IdamClient(null, null) {
    @Override
    public String getAccessToken(String username, String password) {
      return "Bearer " + username + "-" + signIns.incrementAndGet();
    }
  };

  @Test
  void signsInOnceAndReusesTheTokenForHalfAnHour() {
    Instant start = Instant.parse("2026-11-26T10:00:00Z");
    MutableClock clock = new MutableClock(start);
    IdamSystemUser user = new IdamSystemUser(idam, "system", "secret", clock);

    assertThat(user.get()).isEqualTo("Bearer system-1");
    clock.now = start.plus(Duration.ofMinutes(29));
    assertThat(user.get()).isEqualTo("Bearer system-1");
    clock.now = start.plus(Duration.ofMinutes(30));
    assertThat(user.get()).isEqualTo("Bearer system-2");
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
