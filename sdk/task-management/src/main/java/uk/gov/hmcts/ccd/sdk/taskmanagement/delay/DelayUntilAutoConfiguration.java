package uk.gov.hmcts.ccd.sdk.taskmanagement.delay;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.bankholidays.BankHolidays;
import uk.gov.hmcts.ccd.sdk.bankholidays.BankHolidaysAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilInternalConfiguration;

/**
 * Auto-configures the task-management delay-until calculators, which take bank holidays from the
 * SDK's {@link BankHolidays}.
 */
@AutoConfiguration(after = BankHolidaysAutoConfiguration.class)
@Import(DelayUntilInternalConfiguration.class)
public class DelayUntilAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DelayUntilResolver delayUntilResolver(List<DelayUntilCalculator> delayUntilCalculators) {
    return new DelayUntilResolver(delayUntilCalculators);
  }
}
