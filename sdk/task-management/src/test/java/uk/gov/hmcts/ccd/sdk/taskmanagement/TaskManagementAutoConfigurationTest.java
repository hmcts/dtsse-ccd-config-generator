package uk.gov.hmcts.ccd.sdk.taskmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.codec.Decoder;
import feign.codec.Encoder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

class TaskManagementAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class, TaskManagementAutoConfiguration.class))
      .withPropertyValues(
          "task-management.api.url=http://localhost:8080",
          "task-management.outbox.poller.enabled=false"
      )
      .withUserConfiguration(TestConfig.class);

  @Test
  void shouldRegisterCompatibilityCodecBeansByDefault() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(FeignHttpMessageConverters.class);
      assertThat(context).hasBean("compatibilityFeignEncoder");
      assertThat(context).hasBean("compatibilityFeignDecoder");
      assertThat(context).hasSingleBean(Encoder.class);
      assertThat(context).hasSingleBean(Decoder.class);
    });
  }

  @Test
  void shouldPostWithAnOrdinaryConsumerClientWithoutAdditionalConfiguration() throws IOException {
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/messages", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    server.start();

    try {
      contextRunner
          .withUserConfiguration(OrdinaryConsumerConfiguration.class)
          .withPropertyValues("ordinary-consumer.url=http://localhost:" + server.getAddress().getPort())
          .run(context -> context.getBean(OrdinaryConsumerClient.class).send(Map.of("message", "hello")));
    } finally {
      server.stop(0);
    }

    assertThat(requestBody.get()).contains("\"message\":\"hello\"");
  }

  @Test
  void shouldRegisterCompatibilityCodecBeansWhenCalendarBeansExist() {
    contextRunner
        .withUserConfiguration(CalendarCodecConfiguration.class)
        .run(context -> {
          assertThat(context).hasBean("calendarFeignEncoder");
          assertThat(context).hasBean("calendarFeignDecoder");
          assertThat(context).hasBean("compatibilityFeignEncoder");
          assertThat(context).hasBean("compatibilityFeignDecoder");
          assertThat(context.getBeansOfType(Encoder.class)).hasSize(2);
          assertThat(context.getBeansOfType(Decoder.class)).hasSize(2);
        });
  }

  @Test
  void shouldNotRegisterCompatibilityCodecBeansWhenDisabled() {
    contextRunner
        .withPropertyValues("task-management.feign.compat-codecs.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean("compatibilityFeignEncoder");
          assertThat(context).doesNotHaveBean("compatibilityFeignDecoder");
        });
  }

  @Test
  void shouldNotOverrideUserProvidedCompatibilityCodecBeans() {
    contextRunner
        .withUserConfiguration(UserFeignCodecConfiguration.class)
        .run(context -> {
          assertThat(context).hasSingleBean(Encoder.class);
          assertThat(context).hasSingleBean(Decoder.class);
          assertThat(context.getBean(Encoder.class)).isSameAs(context.getBean("feignEncoder"));
          assertThat(context.getBean(Decoder.class)).isSameAs(context.getBean("feignDecoder"));
        });
  }

  @Configuration
  static class TestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
      return mock(NamedParameterJdbcTemplate.class);
    }

    @Bean
    AuthTokenGenerator authTokenGenerator() {
      return () -> "service-token";
    }
  }

  @Configuration
  static class UserFeignCodecConfiguration {
    @Bean("feignEncoder")
    Encoder userFeignEncoder() {
      return mock(Encoder.class);
    }

    @Bean("feignDecoder")
    Decoder userFeignDecoder() {
      return mock(Decoder.class);
    }
  }

  @Configuration
  static class CalendarCodecConfiguration {
    @Bean
    Encoder calendarFeignEncoder() {
      return mock(Encoder.class);
    }

    @Bean
    Decoder calendarFeignDecoder() {
      return mock(Decoder.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableFeignClients(clients = OrdinaryConsumerClient.class)
  static class OrdinaryConsumerConfiguration {
  }

  @FeignClient(name = "ordinary-consumer", url = "${ordinary-consumer.url}")
  interface OrdinaryConsumerClient {
    @PostMapping(path = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    void send(@RequestBody Map<String, String> body);
  }
}
