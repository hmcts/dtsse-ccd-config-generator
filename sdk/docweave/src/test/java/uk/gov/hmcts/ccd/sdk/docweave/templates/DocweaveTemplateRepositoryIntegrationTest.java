package uk.gov.hmcts.ccd.sdk.docweave.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.docweave.DocweaveFlywayAutoConfiguration;

@SpringBootTest(classes = DocweaveTemplateRepositoryIntegrationTest.Config.class, properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
class DocweaveTemplateRepositoryIntegrationTest {
  private final UUID owner = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();

  @Autowired
  private DocweaveTemplateRepository repository;
  @Autowired
  private JdbcTemplate jdbc;
  @Autowired
  private ObjectMapper json;

  @BeforeEach
  void clearTemplates() {
    jdbc.execute("truncate docweave.docweave_template");
  }

  @Test
  void searchesAcrossFieldsAndReturnsAllMatchesAlphabetically() {
    var zebra = repository.create(owner, "Zebra", json.createObjectNode(), List.of(), "possession");
    var alpha = repository.create(other, "alpha possession", json.createObjectNode(),
        List.of(" Urgent "), "witness");

    assertThat(repository.search(owner, "", false))
        .extracting(DocweaveTemplateRepository.Template::id).containsExactly(alpha.id(), zebra.id());
    assertThat(repository.search(owner, "", true))
        .extracting(DocweaveTemplateRepository.Template::id).containsExactly(zebra.id());
    assertThat(repository.search(owner, "POSS urgent wit", false))
        .extracting(DocweaveTemplateRepository.Template::id).containsExactly(alpha.id());
    assertThat(repository.search(owner, "p", false)).hasSize(2);
    assertThat(repository.search(owner, "po", false)).hasSize(2);
    assertThat(repository.search(owner, "absent", false)).isEmpty();
  }

  @Test
  void preservesSuppliedValuesAndUsesDatabaseConstraints() {
    var tags = List.of(" Urgent ", "Urgent", "Urgent");
    var created = repository.create(owner, "  Order  ", json.createObjectNode(), tags, "  body  ");
    assertThat(created.title()).isEqualTo("  Order  ");
    assertThat(created.tags()).containsExactlyElementsOf(tags);
    assertThatThrownBy(() -> repository.create(owner, "   ", json.createObjectNode(), null, null))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> repository.create(owner, "Order", json.createArrayNode(), null, null))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void treatsWildcardsLiterally() {
    repository.create(owner, "100%_done\\path", json.createObjectNode(), List.of(), "");
    repository.create(owner, "100 other", json.createObjectNode(), List.of(), "");
    for (String query : List.of("%", "_", "\\")) {
      assertThat(repository.search(owner, query, false)).hasSize(1);
    }
  }

  @Test
  void updatesAndDeletesOnlyForTheOwnerAndCurrentRevision() {
    var created = repository.create(owner, "Before", json.createObjectNode(), List.of("urgent"), "oldword");
    assertThatThrownBy(() -> repository.delete(created.id(), other, 1))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    assertThat(repository.search(owner, "", false)).containsExactly(created);
    var updated = repository.update(created.id(), owner, 1, "After", json.createObjectNode(), null, "newword");
    assertThat(updated.revision()).isEqualTo(2);
    assertThat(updated.tags()).containsExactly("urgent");
    assertThat(repository.search(owner, "urgent newword", false)).hasSize(1);
    assertThat(repository.search(owner, "oldword", false)).isEmpty();
    assertThatThrownBy(() -> repository.update(created.id(), owner, 1,
        "Stale", json.createObjectNode(), List.of(), ""))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    assertThat(repository.search(owner, "", false)).containsExactly(updated);
    repository.delete(created.id(), owner, 2);
    assertThat(repository.search(owner, "", false)).isEmpty();
  }

  @Configuration
  @Import(DocweaveTemplateRepository.class)
  @ImportAutoConfiguration({
      DecentralisedFlywayAutoConfiguration.class,
      DocweaveFlywayAutoConfiguration.class,
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      FlywayAutoConfiguration.class
  })
  static class Config {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
