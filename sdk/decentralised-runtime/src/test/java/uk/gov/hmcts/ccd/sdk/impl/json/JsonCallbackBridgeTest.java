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

class JsonCallbackBridgeTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void invokesConfiguredPlaceholderCallbackLocally() {
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty("decentralisation.local-callback-placeholder", "ET_COS_URL"),
        new LocalCallbackController()
    );

    Object response = bridge.invoke(
        "${ET_COS_URL}/callbacks/about-to-submit",
        Map.of("event_id", "local")
    );

    assertThat(response)
        .isInstanceOf(Map.class)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("source", "local");
  }

  @Test
  void failsFastWhenCallbackUsesUnknownPlaceholder() {
    JsonCallbackBridge bridge = bridgeWith(new MockEnvironment());

    assertThatThrownBy(() -> bridge.invoke("${MISSING_URL}/callback", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No external callback base URL configured for JSON callback placeholder MISSING_URL");
  }

  @Test
  void acceptsConfiguredExternalPlaceholderAtValidationTime() {
    JsonCallbackBridge bridge = bridgeWith(
        new MockEnvironment().withProperty(
            "decentralisation.external-callback-base-urls[CCD_DEF_AAC_URL]",
            "http://aac.example"
        )
    );

    bridge.validate("${CCD_DEF_AAC_URL}/noc/check-noc-approval");
  }

  @Test
  void failsFastWhenCallbackIsNeitherLocalNorAbsoluteExternalUrl() {
    JsonCallbackBridge bridge = bridgeWith(new MockEnvironment());

    assertThatThrownBy(() -> bridge.invoke("/callback", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No absolute external URL found for JSON callback /callback");
  }

  private JsonCallbackBridge bridgeWith(MockEnvironment environment, Object... controllers) {
    try {
      StaticApplicationContext applicationContext = new StaticApplicationContext();
      for (int i = 0; i < controllers.length; i++) {
        applicationContext.getBeanFactory().registerSingleton("controller-" + i, controllers[i]);
      }
      applicationContext.refresh();

      RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();
      handlerMapping.setApplicationContext(applicationContext);
      handlerMapping.afterPropertiesSet();

      return new JsonCallbackBridge(
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
