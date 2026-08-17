package uk.gov.hmcts.divorce.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///flyway-ordering-data-jdbc",
    "spring.datasource.driverClassName=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class DecentralisedFlywayDataJdbcOrderingTest {

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
    insertCase(2222333344445555L, "{\"sdkMigrationOrderReference\":\" ET-456 \"}");

    assertThatThrownBy(() ->
        insertCase(2222333344445556L, "{\"sdkMigrationOrderReference\":\"et-456\"}"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  private void insertCase(long reference, String data) {
    jdbc.update(INSERT_CASE, reference, reference, data);
  }
}
