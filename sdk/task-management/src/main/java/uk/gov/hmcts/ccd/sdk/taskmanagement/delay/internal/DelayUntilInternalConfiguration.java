package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.bankholidays.BankHolidays;

@Configuration
public class DelayUntilInternalConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DelayUntilDateCalculator delayUntilDateCalculator() {
    return new DelayUntilDateCalculator();
  }

  @Bean
  @ConditionalOnMissingBean
  DelayUntilDateTimeCalculator delayUntilDateTimeCalculator() {
    return new DelayUntilDateTimeCalculator();
  }

  @Bean
  @ConditionalOnMissingBean
  DelayUntilIntervalCalculator delayUntilIntervalCalculator(BankHolidays bankHolidays) {
    return new DelayUntilIntervalCalculator(new WorkingDayIndicator(bankHolidays));
  }
}
