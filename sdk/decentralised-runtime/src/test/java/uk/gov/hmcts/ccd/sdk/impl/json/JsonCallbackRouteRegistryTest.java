package uk.gov.hmcts.ccd.sdk.impl.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class JsonCallbackRouteRegistryTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void invokesCallbackLocallyOnlyWhenCallbackUrlStartsWithConfiguredBaseUrl() throws Exception {
    JsonCallbackRouteRegistry registry = registryWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-base-url", "http://localhost:8080"),
        new LocalCallbackController()
    );

    Object response = registry.invoke(
        "http://localhost:8080/callbacks/about-to-submit",
        Map.of("event_id", "local")
    );

    assertThat(response)
        .isInstanceOf(Map.class)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("source", "local");
  }

  @Test
  void failsFastWhenCallbackIsNeitherLocalNorResolvableExternally() {
    JsonCallbackRouteRegistry registry = registryWith(new MockEnvironment());

    assertThatThrownBy(() -> registry.invoke("${MISSING_URL}/callback", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No resolvable external URL found for JSON callback ${MISSING_URL}/callback");
  }

  private JsonCallbackRouteRegistry registryWith(MockEnvironment environment) {
    return registryWith(environment, new Object[0]);
  }

  private JsonCallbackRouteRegistry registryWith(MockEnvironment environment, Object... controllers) {
    try {
      StaticApplicationContext applicationContext = new StaticApplicationContext();
      for (int i = 0; i < controllers.length; i++) {
        applicationContext.getBeanFactory().registerSingleton("controller-" + i, controllers[i]);
      }
      applicationContext.refresh();

      RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
      handlerMapping.setApplicationContext(applicationContext);
      handlerMapping.afterPropertiesSet();

      return new JsonCallbackRouteRegistry(
          applicationContext,
          mapper,
          handlerMapping,
          environment
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @RestController
  @RequestMapping("/callbacks")
  private static class LocalCallbackController {

    @PostMapping("/about-to-submit")
    Map<String, Object> aboutToSubmit(@RequestBody Map<String, Object> request) {
      return Map.of("source", "local", "event_id", request.get("event_id"));
    }
  }
}
