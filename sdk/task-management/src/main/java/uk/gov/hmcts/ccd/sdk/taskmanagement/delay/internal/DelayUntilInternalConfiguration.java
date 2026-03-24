package uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar.DelayUntilCalendarConfiguration;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.calendar.WorkingDayIndicator;

@Configuration
@Import(DelayUntilCalendarConfiguration.class)
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
  DelayUntilIntervalCalculator delayUntilIntervalCalculator(WorkingDayIndicator workingDayIndicator) {
    return new DelayUntilIntervalCalculator(workingDayIndicator);
  }
}
