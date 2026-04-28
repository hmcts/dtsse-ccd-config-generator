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
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;

@AutoConfiguration
@EnableConfigurationProperties(TaskManagementProperties.class)
@EnableFeignClients(clients = TaskManagementFeignClient.class)
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
  public TaskOutboxCompletionAwaiter taskOutboxCompletionAwaiter(
      TaskOutboxRepository repository,
      TaskManagementProperties properties
  ) {
    return new TaskOutboxCompletionAwaiter(repository, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskOutboxService taskOutboxService(
      TaskOutboxRepository repository,
      TaskOutboxCompletionAwaiter completionAwaiter,
      ObjectMapper objectMapper
  ) {
    return new TaskOutboxService(repository, completionAwaiter, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskOutboxRetryPolicy taskOutboxRetryPolicy(TaskManagementProperties properties) {
    return new TaskOutboxRetryPolicy(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public TaskOutboxTelemetry taskOutboxTelemetry() {
    return event -> {
      // No-op by default. Consumer services can override this bean to emit custom telemetry
      // (for example, Application Insights custom events).
    };
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
      TaskOutboxTelemetry telemetry,
      TaskManagementProperties properties,
      ObjectMapper objectMapper
  ) {
    int batchSize = properties.getOutbox().getPoller().getBatchSize();
    return new TaskOutboxPoller(repository, taskManagementApiClient, retryPolicy, telemetry, batchSize, objectMapper);
  }
}
