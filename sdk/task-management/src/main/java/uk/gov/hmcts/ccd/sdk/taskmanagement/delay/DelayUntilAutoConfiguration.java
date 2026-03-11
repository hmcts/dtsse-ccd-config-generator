package uk.gov.hmcts.ccd.sdk.taskmanagement.delay;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilCalculator;
import uk.gov.hmcts.ccd.sdk.taskmanagement.delay.internal.DelayUntilInternalConfiguration;

/**
 * Auto-configures the task-management delay-until calculators and calendar support.
 *
 * <p>The auto-configuration provides a Caffeine-backed {@code calendarCacheManager}, but does not
 * enable Spring cache advice. Consumers that want public-holiday lookups to be cached must enable
 * caching in their application configuration, for example with {@code @EnableCaching}.
 */
@AutoConfiguration
@Import(DelayUntilInternalConfiguration.class)
public class DelayUntilAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DelayUntilResolver delayUntilResolver(List<DelayUntilCalculator> delayUntilCalculators) {
    return new DelayUntilResolver(delayUntilCalculators);
  }
}
