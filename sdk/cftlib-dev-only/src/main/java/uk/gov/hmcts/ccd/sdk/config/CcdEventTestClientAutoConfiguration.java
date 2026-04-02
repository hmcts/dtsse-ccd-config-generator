package uk.gov.hmcts.ccd.sdk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.ServiceEventTestClient;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;

@AutoConfiguration
@ConditionalOnClass({CoreCaseDataApi.class, ResolvedConfigRegistry.class, ObjectMapper.class})
@ConditionalOnBean(CoreCaseDataApi.class)
public class CcdEventTestClientAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ServiceEventTestClient serviceEventTestClient(
      CoreCaseDataApi ccdApi,
      ObjectMapper objectMapper,
      ResolvedConfigRegistry resolvedConfigRegistry
  ) {
    return new ServiceEventTestClient(ccdApi, objectMapper, resolvedConfigRegistry);
  }
}
