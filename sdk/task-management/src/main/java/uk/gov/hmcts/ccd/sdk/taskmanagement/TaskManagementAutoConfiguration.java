package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.codec.Decoder;
import feign.codec.Encoder;
import java.time.Duration;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;

@AutoConfiguration
@EnableConfigurationProperties(TaskManagementProperties.class)
@EnableFeignClients(clients = TaskManagementFeignClient.class)
public class TaskManagementAutoConfiguration {

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "task-management.feign",
      name = "inherit-parent-configuration",
      havingValue = "true"
  )
  public FeignClientConfigurer feignClientConfigurer() {
    return new FeignClientConfigurer() {
      @Override
      public boolean inheritParentConfiguration() {
        return true;
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(Encoder.class)
  @ConditionalOnProperty(
      prefix = "task-management.feign.compat-codecs",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true
  )
  public Encoder compatibilityFeignEncoder(ObjectFactory<HttpMessageConverters> converters) {
    return new SpringEncoder(converters);
  }

  @Bean
  @ConditionalOnMissingBean(Decoder.class)
  @ConditionalOnProperty(
      prefix = "task-management.feign.compat-codecs",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true
  )
  public Decoder compatibilityFeignDecoder(ObjectFactory<HttpMessageConverters> converters) {
    return new ResponseEntityDecoder(new SpringDecoder(converters));
  }

  @Bean
  @ConditionalOnMissingBean(AuthTokenGenerator.class)
  @ConditionalOnBean(ServiceAuthorisationApi.class)
  @ConditionalOnProperty(prefix = "idam.s2s-auth", name = {"secret", "microservice"})
  public AuthTokenGenerator taskManagementAuthTokenGenerator(
      @Value("${idam.s2s-auth.secret}") String secret,
      @Value("${idam.s2s-auth.microservice}") String microService,
      ServiceAuthorisationApi serviceAuthorisationApi
  ) {
    return AuthTokenGeneratorFactory.createDefaultGenerator(
        secret,
        microService,
        serviceAuthorisationApi,
        Duration.ofMinutes(5)
    );
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnClass({TaskManagementFeignClient.class})
  public TaskManagementApiClient taskManagementApiClient(TaskManagementFeignClient feignClient) {
    return new TaskManagementApiClient(feignClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskOutboxRepository taskOutboxRepository(
      NamedParameterJdbcTemplate jdbc,
      TaskManagementProperties properties
  ) {
    return new TaskOutboxRepository(jdbc, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskOutboxService taskOutboxService(TaskOutboxRepository repository, ObjectMapper objectMapper) {
    return new TaskOutboxService(repository, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskOutboxRetryPolicy taskOutboxRetryPolicy(TaskManagementProperties properties) {
    return new TaskOutboxRetryPolicy(properties);
  }

  @Bean
  @ConditionalOnProperty(
      name = "task-management.outbox.poller.enabled",
      havingValue = "true",
      matchIfMissing = true
  )
  public TaskOutboxPoller taskOutboxPoller(
      TaskOutboxRepository repository,
      TaskManagementApiClient taskManagementApiClient,
      TaskOutboxRetryPolicy retryPolicy,
      TaskManagementProperties properties,
      ObjectMapper objectMapper
  ) {
    int batchSize = properties.getOutbox().getPoller().getBatchSize();
    return new TaskOutboxPoller(repository, taskManagementApiClient, retryPolicy, batchSize, objectMapper);
  }
}
