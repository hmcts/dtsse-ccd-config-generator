package uk.gov.hmcts.ccd.sdk.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.CaseSubmissionService;

@TestConfiguration(proxyBeanMethods = false)
public class CcdEventTestConfiguration {

  @Bean
  @Primary
  TestIdamService ccdSdkTestIdamService() {
    return new TestIdamService();
  }

  /** Resolve the case and state types from each injection point. */
  @Bean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  @SuppressWarnings("unchecked")
  <Case, State extends Enum<State>> CcdEventTestSupport<Case, State> ccdEventTestSupport(
      InjectionPoint injectionPoint,
      ResolvedConfigRegistry registry,
      CaseSubmissionService submissionService,
      JdbcTemplate jdbc,
      @Qualifier(CcdCaseDataMapperConfiguration.CCD_CASE_DATA_OBJECT_MAPPER) ObjectMapper mapper,
      TestIdamService idam) {
    ResolvableType type = injectionPoint.getField() == null
        ? ResolvableType.forMethodParameter(injectionPoint.getMethodParameter())
        : ResolvableType.forField(injectionPoint.getField());
    Class<?> caseClass = type.as(CcdEventTestSupport.class).getGeneric(0).resolve();
    Class<?> stateClass = type.as(CcdEventTestSupport.class).getGeneric(1).resolve();
    if (caseClass == null || stateClass == null || !stateClass.isEnum()) {
      throw new IllegalArgumentException("Inject CcdEventTestSupport with concrete case and enum state types");
    }
    return new CcdEventTestSupport<>(
        (Class<Case>) caseClass,
        (Class<State>) stateClass,
        registry,
        submissionService,
        jdbc,
        mapper,
        idam
    );
  }
}
