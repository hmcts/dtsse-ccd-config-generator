package uk.gov.hmcts.ccd.sdk.bankholidays;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ccd.bank-holidays")
public class BankHolidaysProperties {

  /** GOV.UK's bank holidays feed. */
  private URI url = URI.create("https://www.gov.uk/bank-holidays.json");
}
