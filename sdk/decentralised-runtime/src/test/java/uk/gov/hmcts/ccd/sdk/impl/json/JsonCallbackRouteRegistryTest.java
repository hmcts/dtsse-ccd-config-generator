package uk.gov.hmcts.ccd.sdk.impl.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class JsonCallbackRouteRegistryTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void invokesPlaceholderCallbackExternallyWhenNoLocalRouteExists() throws Exception {
    ArrayBlockingQueue<CapturedRequest> requests = new ArrayBlockingQueue<>(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/noc/check-noc-approval", exchange -> {
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      requests.add(new CapturedRequest(
          exchange.getRequestMethod(),
          exchange.getRequestURI().getPath(),
          exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION),
          exchange.getRequestHeaders().getFirst("ServiceAuthorization"),
          new String(requestBody, UTF_8)
      ));

      byte[] responseBody = "{\"confirmation_header\":\"done\"}".getBytes(UTF_8);
      exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
      exchange.sendResponseHeaders(200, responseBody.length);
      exchange.getResponseBody().write(responseBody);
      exchange.close();
    });

    server.start();
    try {
      setCurrentHeaders();
      JsonCallbackRouteRegistry registry = registryWith(
          new MockEnvironment().withProperty("CCD_DEF_AAC_URL", "http://localhost:"
              + server.getAddress().getPort())
      );

      Object response = registry.invoke("${CCD_DEF_AAC_URL}/noc/check-noc-approval",
          Map.of("event_id", "nocRequest"));

      CapturedRequest request = requests.poll(5, SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.path()).isEqualTo("/noc/check-noc-approval");
      assertThat(request.authorization()).isEqualTo("Bearer user");
      assertThat(request.serviceAuthorization()).isEqualTo("Bearer service");
      assertThat(request.body()).contains("\"event_id\":\"nocRequest\"");
      assertThat(response)
          .isInstanceOf(Map.class)
          .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
          .containsEntry("confirmation_header", "done");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void failsFastWhenCallbackIsNeitherLocalNorResolvableExternally() {
    JsonCallbackRouteRegistry registry = registryWith(new MockEnvironment());

    assertThatThrownBy(() -> registry.invoke("${MISSING_URL}/callback", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No local Spring POST handler found for JSON callback ${MISSING_URL}/callback");
  }

  private JsonCallbackRouteRegistry registryWith(MockEnvironment environment) {
    return new JsonCallbackRouteRegistry(
        new StaticApplicationContext(),
        mapper,
        new RequestMappingHandlerMapping(),
        environment
    );
  }

  private void setCurrentHeaders() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user");
    request.addHeader("ServiceAuthorization", "Bearer service");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private record CapturedRequest(String method,
                                 String path,
                                 String authorization,
                                 String serviceAuthorization,
                                 String body) {
  }
}
