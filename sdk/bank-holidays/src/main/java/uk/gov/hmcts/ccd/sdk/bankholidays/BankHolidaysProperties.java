package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where bank holidays are fetched from, and how often. */
@Getter
@Setter
@ConfigurationProperties(prefix = "ccd.bank-holidays")
public class BankHolidaysProperties {

  /** GOV.UK's bank holidays feed. Blank never fetches, keeping the SDK's copy, as for tests. */
  private String url = "https://www.gov.uk/bank-holidays.json";

  /** How long to wait for GOV.UK to connect and to answer. */
  private Duration timeout = Duration.ofSeconds(10);

  /** How old the last successful fetch may be before an instance fetches again. */
  private Duration maxAge = Duration.ofDays(1);

  private final Refresh refresh = new Refresh();

  /** How often each instance checks, which is also how soon a failed fetch is tried again. */
  @Getter
  @Setter
  public static class Refresh {

    private Duration delay = Duration.ofHours(1);

    private Duration initialDelay = Duration.ZERO;
  }
}
