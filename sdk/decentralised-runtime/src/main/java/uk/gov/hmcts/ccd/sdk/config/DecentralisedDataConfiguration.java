package uk.gov.hmcts.ccd.sdk.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.config.MessagingProperties;

@AutoConfiguration(
    before = {FlywayAutoConfiguration.class}
)
@ComponentScan(basePackageClasses = {MessagingProperties.class})
@Import(DecentralisedFlywayAutoConfiguration.class)
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
public class DecentralisedDataConfiguration {
}
