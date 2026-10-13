package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.DefinitionMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.json.TestJsonCallbackBridge;
import uk.gov.hmcts.ccd.sdk.json.JsonCCDConfigSupport;

/**
 * Boot configuration shared by focused event submission tests. It is a test component so an
 * application that scans {@code uk.gov.hmcts.ccd.sdk} does not pick it up.
 */
@TestComponent
@SuppressWarnings("removal") // Jackson2AutoConfiguration is intentionally used while the SDK remains on Jackson 2.
@Configuration(proxyBeanMethods = false)
@Import({JsonCCDConfigSupport.class, TestJsonCallbackBridge.class})
@ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    Jackson2AutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    DefinitionMapperConfiguration.class,
    CcdCaseDataMapperConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    TransactionAutoConfiguration.class,
    FlywayAutoConfiguration.class,
    DecentralisedFlywayAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class
})
public class CcdSdkTestApplication {

  @Bean
  ResolvedConfigRegistry resolvedConfigRegistry(List<CCDConfig<?, ?, ?>> configs) {
    return new ResolvedConfigRegistry(new CCDDefinitionGenerator(configs, null).loadConfigs());
  }
}
