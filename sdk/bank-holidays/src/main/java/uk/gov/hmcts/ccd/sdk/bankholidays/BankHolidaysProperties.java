package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where bank holidays are fetched from. The refresh schedule is {@code ccd.bank-holidays.refresh.delay}
 * (default six hours) after {@code ccd.bank-holidays.refresh.initial-delay} (default none).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ccd.bank-holidays")
public class BankHolidaysProperties {

  /** GOV.UK's bank holidays feed. Blank never fetches, keeping the SDK's copy, as for tests. */
  private String url = "https://www.gov.uk/bank-holidays.json";

  /** How long to wait for GOV.UK to connect and to answer. */
  private Duration timeout = Duration.ofSeconds(10);
}
