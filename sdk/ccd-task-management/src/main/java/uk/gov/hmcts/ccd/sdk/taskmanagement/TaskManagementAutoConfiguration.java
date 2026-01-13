package uk.gov.hmcts.ccd.sdk.taskmanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;

@AutoConfiguration
@EnableConfigurationProperties(TaskManagementProperties.class)
public class TaskManagementAutoConfiguration {

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
  @ConditionalOnClass(RestTemplateBuilder.class)
  public TaskManagementApiClient taskManagementApiClient(
      RestTemplateBuilder builder,
      AuthTokenGenerator authTokenGenerator,
      TaskManagementProperties properties,
      ObjectMapper objectMapper
  ) {
    return new TaskManagementApiClient(builder, authTokenGenerator, properties, objectMapper);
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
      TaskManagementProperties properties
  ) {
    int batchSize = properties.getOutbox().getPoller().getBatchSize();
    return new TaskOutboxPoller(repository, taskManagementApiClient, retryPolicy, batchSize);
  }
}
