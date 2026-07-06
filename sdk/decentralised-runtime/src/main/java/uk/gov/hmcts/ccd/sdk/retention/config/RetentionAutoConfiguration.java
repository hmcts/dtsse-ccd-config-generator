package uk.gov.hmcts.ccd.sdk.retention.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.ccd.sdk.retention.CaseRetentionService;
import uk.gov.hmcts.ccd.sdk.retention.CaseRetentionTask;
import uk.gov.hmcts.ccd.sdk.retention.CcdCaseDataExistenceClient;
import uk.gov.hmcts.ccd.sdk.retention.RetentionCaseDataRepository;
import uk.gov.hmcts.ccd.sdk.retention.RetentionProperties;
import uk.gov.hmcts.ccd.sdk.retention.client.CcdDataStoreRetentionFeignClient;
import uk.gov.hmcts.ccd.sdk.retention.client.FeignCcdCaseDataExistenceClient;
import uk.gov.hmcts.ccd.sdk.retention.client.RetentionSystemUserTokenProvider;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGeneratorFactory;
import uk.gov.hmcts.reform.idam.client.IdamClient;

@AutoConfiguration
@EnableConfigurationProperties(RetentionProperties.class)
@EnableFeignClients(clients = CcdDataStoreRetentionFeignClient.class)
@ConditionalOnProperty(
    prefix = "ccd.decentralised-runtime.retention",
    name = "enabled",
    havingValue = "true"
)
public class RetentionAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(AuthTokenGenerator.class)
  @ConditionalOnBean(ServiceAuthorisationApi.class)
  @ConditionalOnProperty(prefix = "idam.s2s-auth", name = {"secret", "microservice"})
  public AuthTokenGenerator retentionAuthTokenGenerator(
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
  public RetentionCaseDataRepository retentionCaseDataRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    return new RetentionCaseDataRepository(jdbcTemplate);
  }

  @Bean
  @ConditionalOnMissingBean
  public RetentionSystemUserTokenProvider retentionSystemUserTokenProvider(IdamClient idamClient,
                                                                           RetentionProperties properties) {
    return new RetentionSystemUserTokenProvider(idamClient, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public CcdCaseDataExistenceClient ccdCaseDataExistenceClient(
      CcdDataStoreRetentionFeignClient feignClient,
      RetentionSystemUserTokenProvider systemUserTokenProvider
  ) {
    return new FeignCcdCaseDataExistenceClient(feignClient, systemUserTokenProvider);
  }

  @Bean
  @ConditionalOnMissingBean
  public CaseRetentionService caseRetentionService(RetentionCaseDataRepository repository,
                                                   CcdCaseDataExistenceClient ccdCaseDataExistenceClient) {
    return new CaseRetentionService(repository, ccdCaseDataExistenceClient);
  }

  @Bean
  @ConditionalOnMissingBean
  public CaseRetentionTask caseRetentionTask(CaseRetentionService caseRetentionService,
                                             RetentionProperties properties) {
    return new CaseRetentionTask(caseRetentionService, properties);
  }
}
