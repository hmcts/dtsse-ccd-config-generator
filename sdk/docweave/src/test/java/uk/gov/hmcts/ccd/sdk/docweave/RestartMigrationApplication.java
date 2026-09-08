package uk.gov.hmcts.ccd.sdk.docweave;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

/** Spring startup fixture loaded through the real devtools restart classloader. */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({
    DocweaveFlywayAutoConfiguration.class,
    DecentralisedFlywayAutoConfiguration.class,
    DataSourceAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
public class RestartMigrationApplication {

  public static int migrateAndCountTemplates(String url, String username, String password) {
    int[] count = new int[1];
    new ApplicationContextRunner()
        .withUserConfiguration(RestartMigrationApplication.class)
        .withPropertyValues("spring.datasource.url=" + url,
            "spring.datasource.username=" + username, "spring.datasource.password=" + password)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var jdbc = context.getBean(JdbcTemplate.class);
          assertThat(jdbc.queryForObject("select to_regclass('docweave.docweave_template')::text", String.class))
              .isEqualTo("docweave.docweave_template");
          assertThat(jdbc.queryForObject(
              "select count(*) from docweave.flyway_schema_history where version = '0001' and success",
              Integer.class)).isEqualTo(1);
          jdbc.execute("""
              insert into docweave.docweave_template (id, owner_id, title, content, searchable_text)
              values ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
                      'Survives restart', '{}', '') on conflict (id) do nothing
              """);
          count[0] = jdbc.queryForObject("select count(*) from docweave.docweave_template", Integer.class);
        });
    return count[0];
  }
}
