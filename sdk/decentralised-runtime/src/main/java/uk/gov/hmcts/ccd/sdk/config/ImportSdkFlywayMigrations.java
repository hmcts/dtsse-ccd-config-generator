package uk.gov.hmcts.ccd.sdk.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

/**
 * Imports the auto-configurations that register an {@link SdkFlywayMigration}, and nothing else from their libraries.
 *
 * <p>A library lists its migration auto-configuration in
 * {@code META-INF/spring/uk.gov.hmcts.ccd.sdk.config.ImportSdkFlywayMigrations.imports} as well as in
 * {@code AutoConfiguration.imports}, so {@link SdkDatabaseMigration} can run its migrations outside the application.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ImportAutoConfiguration
public @interface ImportSdkFlywayMigrations {
}
