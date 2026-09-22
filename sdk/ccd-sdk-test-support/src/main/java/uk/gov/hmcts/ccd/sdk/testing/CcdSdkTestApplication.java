package uk.gov.hmcts.ccd.sdk.testing;

import java.util.List;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
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

/** Boot configuration shared by focused event submission tests. */
@Configuration(proxyBeanMethods = false)
@Import({JsonCCDConfigSupport.class, TestJsonCallbackBridge.class})
@ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    JacksonAutoConfiguration.class,
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
