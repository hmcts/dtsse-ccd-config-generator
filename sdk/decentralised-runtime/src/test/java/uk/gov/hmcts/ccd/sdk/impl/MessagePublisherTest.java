package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.ccd.config.MessagingPropertiesAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.DefinitionMapperConfiguration;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;

class MessagePublisherTest {

  @TempDir
  Path snapshotDirectory;

  @Test
  void publishesNothingWhenMessagingIsDisabled() {
    contextRunner().run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(MessagePublisher.class);
    });
  }

  @Test
  void startsWithoutSnapshotsWhenMessagingIsEnabled() {
    contextRunner()
        .withPropertyValues("ccd.messaging.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(MessagePublisher.class);
        });
  }

  @Test
  void failsTheEventWhenItsCaseTypeHasNoSnapshot() {
    contextRunner()
        .withPropertyValues("ccd.messaging.enabled=true")
        .run(context -> assertThatThrownBy(() -> context.getBean(MessagePublisher.class).publishEvent(
                1L,
                "user",
                "event",
                "Submitted",
                CaseDetails.builder().caseTypeId("ET_EnglandWales").build(),
                1L,
                LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("CCD messaging is enabled but there is no definition snapshot for case type ET_EnglandWales"));
  }

  private ApplicationContextRunner contextRunner() {
    var mapper = new DefinitionMapperConfiguration().definitionMapper();
    var registry = new DefinitionRegistry(mapper, snapshotDirectory.toFile());

    return new ApplicationContextRunner()
        .withUserConfiguration(MessagingPropertiesAutoConfiguration.class, MessagePublisher.class)
        .withBean(DefinitionRegistry.class, () -> registry)
        .withBean("ccd_mapper", ObjectMapper.class, () -> mapper)
        .withBean(NamedParameterJdbcTemplate.class,
            () -> new NamedParameterJdbcTemplate(new DriverManagerDataSource()));
  }
}
