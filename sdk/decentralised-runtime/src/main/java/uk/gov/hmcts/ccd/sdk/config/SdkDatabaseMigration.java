package uk.gov.hmcts.ccd.sdk.config;

import java.util.stream.Stream;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the SDK libraries' migrations and then the application's, as the application would at startup, and exits.
 *
 * <p>The CCD SDK Gradle plugin's {@code migratePostgresDatabase} task runs this, so a pipeline can migrate as the
 * schema owner while the deployed application connects as a role without DDL and runs with
 * {@code spring.flyway.enabled=false}. Only the datasource, Flyway and the libraries' migration registrations are
 * loaded: the application's own beans and the rest of the SDK are not.
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
@ImportSdkFlywayMigrations
public class SdkDatabaseMigration {

  public static void main(String[] args) {
    // A deployed application turns Flyway off, and this is where it runs instead.
    String[] arguments = Stream.concat(Stream.of("--spring.flyway.enabled=true"), Stream.of(args))
        .toArray(String[]::new);
    new SpringApplicationBuilder(SdkDatabaseMigration.class)
        .web(WebApplicationType.NONE)
        .bannerMode(Banner.Mode.OFF)
        .run(arguments)
        .close();
  }
}
