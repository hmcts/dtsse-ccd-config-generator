package uk.gov.hmcts.ccd.sdk.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class CcdDataMigrationAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(CcdDataMigrationAutoConfiguration.class))
      .withBean(DataSource.class, () -> mock(DataSource.class))
      .withBean(
          NamedParameterJdbcTemplate.class,
          () -> new NamedParameterJdbcTemplate(mock(DataSource.class))
      )
      .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

  @Test
  void doesNotCreateTaskBeanByDefault() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(CcdDataMigrationTask.class));
  }

  @Test
  void createsTaskBeanWhenEnabled() {
    contextRunner
        .withPropertyValues(
            "ccd.data-migration.enabled=true",
            "ccd.data-migration.case-type-ids[0]=TestCase",
            "ccd.data-migration.source-jurisdiction=TEST",
            "ccd.data-migration.event-id-window-size=500",
            "ccd.data-migration.max-batches-per-run=10"
        )
        .run(context -> assertThat(context).hasSingleBean(CcdDataMigrationTask.class));
  }

  @Test
  void failsClearlyWhenEnabledWithoutCaseTypeIds() {
    contextRunner
        .withPropertyValues("ccd.data-migration.enabled=true")
        .run(context -> assertThat(context.getStartupFailure())
            .hasMessageContaining("ccd.data-migration.case-type-ids must be configured when enabled"));
  }

  @Test
  void failsClearlyWhenEnabledWithoutSourceJurisdiction() {
    contextRunner
        .withPropertyValues(
            "ccd.data-migration.enabled=true",
            "ccd.data-migration.case-type-ids[0]=TestCase"
        )
        .run(context -> assertThat(context.getStartupFailure())
            .hasMessageContaining("ccd.data-migration.source-jurisdiction must be configured when enabled"));
  }

  @Test
  void backsOffWhenServiceProvidesTaskBean() {
    contextRunner
        .withBean(CcdDataMigrationTask.class, () -> new CcdDataMigrationTask(
            new NamedParameterJdbcTemplate(mock(DataSource.class)),
            mock(PlatformTransactionManager.class),
            CcdDataMigrationTaskOptions.builder(List.of("ServiceCase"))
                .sourceJurisdiction("TEST")
                .build(),
            () -> false
        ))
        .withPropertyValues("ccd.data-migration.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(CcdDataMigrationTask.class));
  }
}
