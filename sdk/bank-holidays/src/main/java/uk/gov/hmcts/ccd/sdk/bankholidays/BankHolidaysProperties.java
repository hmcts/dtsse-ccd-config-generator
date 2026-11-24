package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where bank holidays are fetched from, and how often. */
@Getter
@Setter
@ConfigurationProperties(prefix = "ccd.bank-holidays")
public class BankHolidaysProperties {

  /** GOV.UK's bank holidays feed. */
  private URI url = URI.create("https://www.gov.uk/bank-holidays.json");

  /** How long to wait for GOV.UK to connect and to answer. */
  private Duration timeout = Duration.ofSeconds(10);

  /** How old the last successful fetch may be before an instance fetches again. */
  private Duration maxAge = Duration.ofDays(1);

  private final Refresh refresh = new Refresh();

  @Getter
  @Setter
  public static class Refresh {

    /**
     * Whether to fetch from GOV.UK. Off, the bank holidays are those already stored, or the SDK's
     * copy, as for tests.
     */
    private boolean enabled = true;

    /** How often each instance checks, which is also how soon a failed fetch is tried again. */
    private Duration delay = Duration.ofHours(1);

    /** How long after startup the first check is. */
    private Duration initialDelay = Duration.ZERO;
  }
}
