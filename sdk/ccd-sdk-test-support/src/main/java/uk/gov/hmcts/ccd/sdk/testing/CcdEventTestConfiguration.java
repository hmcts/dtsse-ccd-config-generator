package uk.gov.hmcts.ccd.sdk.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.SpringBootMockMvcBuilderCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
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

  /**
   * Answers the application's own Feign calls to IDAM, S2S and role assignment from the actors
   * test support registers, when the application uses Spring Cloud OpenFeign.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "feign.Capability")
  static class FeignIdentity {

    @Bean
    feign.Capability ccdSdkTestIdentity(TestActors actors) {
      return new TestIdentityCapability(actors);
    }
  }

  /**
   * Answers the application's OAuth2 client token requests, such as for the system user it runs
   * background work as, with the default test user's token, when the application uses Spring
   * Security's OAuth2 client.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager")
  static class OAuth2ClientIdentity {

    @Bean
    @Primary
    OAuth2AuthorizedClientManager ccdSdkTestAuthorizedClientManager(
        ObjectProvider<ClientRegistrationRepository> registrations) {
      return request -> new OAuth2AuthorizedClient(
          registrations.getObject().findByRegistrationId(request.getClientRegistrationId()),
          request.getPrincipal().getName(),
          new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
              TestIdamService.DEFAULT_TOKEN.substring("Bearer ".length()),
              Instant.now(), Instant.now().plus(Duration.ofHours(8))));
    }
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
