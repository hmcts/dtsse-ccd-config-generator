package uk.gov.hmcts.ccd.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.Jackson2ObjectMapperFactoryBean;
import uk.gov.hmcts.ccd.domain.model.definition.CaseDetails;
import uk.gov.hmcts.ccd.sdk.jackson.UnwrappedPrefixModule;

class CcdWireFormatConfigurationTest {

  @Test
  void everyMapperBeanGetsTheWireFormatAndTheUnwrappedPrefixFix() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CcdJacksonConfiguration.class, CcdWireFormatConfiguration.class))
        .withBean("serviceMapper", ObjectMapper.class, () -> JsonMapper.builder().build())
        .withBean("factoryMapper", Jackson2ObjectMapperFactoryBean.class, Jackson2ObjectMapperFactoryBean::new)
        .run(context -> {
          for (String name : new String[] {"serviceMapper", "factoryMapper"}) {
            ObjectMapper mapper = context.getBean(name, ObjectMapper.class);

            CaseDetails details = mapper.readValue("{\"jurisdiction\":\"DIVORCE\",\"a_field_ccd_added_later\":true}", CaseDetails.class);

            assertThat(details.getJurisdiction()).isEqualTo("DIVORCE");
            assertThat(mapper.getRegisteredModuleIds()).contains(UnwrappedPrefixModule.class.getName());
          }
        });
  }
}
