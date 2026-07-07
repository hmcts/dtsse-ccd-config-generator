package uk.gov.hmcts.divorce.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationException;
import uk.gov.hmcts.ccd.sdk.migration.CcdDataMigrationTask;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///e2e",
    "spring.datasource.driverClassName=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.jms.servicebus.enabled=false",
    "spring.autoconfigure.exclude=com.azure.spring.cloud.autoconfigure.implementation.jms.ServiceBusJmsAutoConfiguration",
    "ccd.data-migration.enabled=true",
    "ccd.data-migration.case-type-ids[0]=NFD",
    "ccd.data-migration.source-jurisdiction=DIVORCE",
    "ccd.sdk.decentralised=true"
})
class CcdDataMigrationSpringBootTest {

  @Autowired
  private CcdDataMigrationTask migrationTask;

  @Test
  void wiresMigrationTaskInRepresentativeSpringBootAppButRefusesToRunWhenDecentralisedRuntimeIsEnabled() {
    assertThat(migrationTask).isNotNull();

    assertThatThrownBy(migrationTask::runMigration)
        .isInstanceOf(CcdDataMigrationException.class)
        .hasMessageContaining("cannot run while the decentralised runtime is enabled");
  }
}
