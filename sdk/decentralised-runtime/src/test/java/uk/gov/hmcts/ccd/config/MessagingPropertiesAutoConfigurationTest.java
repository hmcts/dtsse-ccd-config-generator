package uk.gov.hmcts.ccd.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingPropertiesAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(MessagingPropertiesAutoConfiguration.class));

  @Test
  void shouldNotCreateMessagingPropertiesByDefault() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean("messagingProperties"));
  }

  @Test
  void shouldNotCreateMessagingPropertiesWhenDisabled() {
    contextRunner
        .withPropertyValues("ccd.messaging.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean("messagingProperties"));
  }

  @Test
  void shouldCreateMessagingPropertiesWhenEnabled() {
    contextRunner
        .withPropertyValues("ccd.messaging.enabled=true")
        .run(context -> assertThat(context).hasBean("messagingProperties"));
  }
}
