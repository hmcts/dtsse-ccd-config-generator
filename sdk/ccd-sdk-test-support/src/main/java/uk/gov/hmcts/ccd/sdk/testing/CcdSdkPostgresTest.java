package uk.gov.hmcts.ccd.sdk.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/** Starts a PostgreSQL Testcontainer and enables event testing in an existing Spring test context. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@EnableCcdEventTesting
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:15-alpine:///ccd",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
})
public @interface CcdSdkPostgresTest {
}
