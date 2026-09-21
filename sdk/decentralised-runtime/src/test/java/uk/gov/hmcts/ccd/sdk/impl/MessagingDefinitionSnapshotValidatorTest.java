package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.ccd.sdk.config.DefinitionMapperConfiguration;

class MessagingDefinitionSnapshotValidatorTest {

  @TempDir
  Path snapshotDirectory;

  @Test
  void allowsMissingSnapshotsWhenMessagingIsDisabled() {
    contextRunner().run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(MessagingDefinitionSnapshotValidator.class);
    });
  }

  @Test
  void failsStartupWhenMessagingIsEnabledWithoutSnapshots() {
    contextRunner()
        .withPropertyValues("ccd.messaging.enabled=true")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("CCD messaging is enabled but no definition snapshots were found"));
  }

  @Test
  void startsWhenMessagingIsEnabledWithSnapshots() throws IOException {
    Files.writeString(snapshotDirectory.resolve("ET_EnglandWales.json"), "{}");

    contextRunner()
        .withPropertyValues("ccd.messaging.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(MessagingDefinitionSnapshotValidator.class);
        });
  }

  private ApplicationContextRunner contextRunner() {
    var mapper = new DefinitionMapperConfiguration().definitionMapper();
    var registry = new DefinitionRegistry(mapper, snapshotDirectory.toFile());

    return new ApplicationContextRunner()
        .withUserConfiguration(MessagingDefinitionSnapshotValidator.class)
        .withBean(DefinitionRegistry.class, () -> registry);
  }
}
