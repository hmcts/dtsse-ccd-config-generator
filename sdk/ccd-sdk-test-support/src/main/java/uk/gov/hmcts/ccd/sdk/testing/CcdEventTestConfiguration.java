package uk.gov.hmcts.ccd.sdk.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.SpringBootMockMvcBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uk.gov.hmcts.ccd.sdk.ResolvedConfigRegistry;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;

@TestConfiguration(proxyBeanMethods = false)
public class CcdEventTestConfiguration {

  @Bean
  TestActors ccdSdkTestActors() {
    return new TestActors();
  }

  @Bean
  @Primary
  TestIdamService ccdSdkTestIdamService(TestActors actors) {
    return new TestIdamService(actors);
  }

  @Bean
  static TestServiceAuthorisation ccdSdkTestServiceAuthorisation() {
    return new TestServiceAuthorisation();
  }

  /** Resolve the case and state types from each injection point. */
  @Bean
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  @SuppressWarnings("unchecked")
  <Case, State extends Enum<State>> CcdEventTestSupport<Case, State> ccdEventTestSupport(
      InjectionPoint injectionPoint,
      ResolvedConfigRegistry registry,
      ApplicationContext context,
      JdbcTemplate jdbc,
      @Qualifier(CcdCaseDataMapperConfiguration.CCD_CASE_DATA_OBJECT_MAPPER) ObjectMapper mapper,
      TestActors actors) {
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
        mockMvc(context),
        jdbc,
        mapper,
        actors
    );
  }

  /** Sends requests through the application's servlet filters, as CCD's calls to it would. */
  private static MockMvc mockMvc(ApplicationContext context) {
    if (!(context instanceof WebApplicationContext web)) {
      throw new IllegalStateException("CCD event testing calls the application's endpoints; "
          + "run the test in a servlet web application context");
    }
    DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(web);
    SpringBootMockMvcBuilderCustomizer customizer = new SpringBootMockMvcBuilderCustomizer(web);
    // Printing registers a context bean, which fails when a second helper is built.
    customizer.setPrint(MockMvcPrint.NONE);
    customizer.customize(builder);
    return builder.build();
  }
}
