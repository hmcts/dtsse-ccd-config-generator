package uk.gov.hmcts.divorce.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///flyway-ordering-data-jpa",
    "spring.datasource.driverClassName=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class DecentralisedFlywayDataJpaOrderingTest {

  private static final String INSERT_CASE = """
      insert into ccd.case_data (
          reference,
          id,
          security_classification,
          jurisdiction,
          case_type_id,
          state,
          data
      ) values (?, ?, 'PUBLIC', 'DIVORCE', 'TestCaseType', 'Submitted', cast(? as jsonb))
      """;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void applicationMigrationExtendsSdkManagedCaseDataAfterSdkMigrations() {
    insertCase(3333444455556666L, "{\"sdkMigrationOrderReference\":\" ET-789 \"}");

    assertThatThrownBy(() ->
        insertCase(3333444455556667L, "{\"sdkMigrationOrderReference\":\"et-789\"}"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  private void insertCase(long reference, String data) {
    jdbc.update(INSERT_CASE, reference, reference, data);
  }
}
