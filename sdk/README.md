# CCD SDK libraries

The SDK is a collection of independently published libraries, each of which may persist data in its own database schema with its own flyway migration history.

## Adding migrations to a library

Place migrations under a library-specific resource path:

```text
src/main/resources/<library>-db/migration/
├── V0001__create_schema_objects.sql
└── V0002__add_lookup_index.sql
```

Add a dedicated Spring Boot auto-configuration that registers your migrations with the central coordinator:

```java
package uk.gov.hmcts.ccd.sdk.documents;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.config.SdkFlywayMigration;

@AutoConfiguration
public class DocumentsFlywayAutoConfiguration {

  @Bean
  SdkFlywayMigration documentsMigrations() {
    return new SdkFlywayMigration(
        DocumentsFlywayAutoConfiguration.class,
        "documents",
        "classpath:documents-db/migration"
    );
  }
}
```

Register the auto-configuration in:

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

```text
uk.gov.hmcts.ccd.sdk.documents.DocumentsFlywayAutoConfiguration
```

## Library dependencies

A library may depend on other migrations:

```java
return new SdkFlywayMigration(
    DocumentTasksFlywayAutoConfiguration.class,
    "document_tasks",
    "classpath:document-tasks-db/migration",
    DocumentsFlywayAutoConfiguration.class,
    TaskManagementFlywayAutoConfiguration.class
);
```

## Migration rules

- Never modify, rename, reorder, or delete a released migration.
- Libraries must not make changes outside of their own schema.

## Migration execution

The decentralised runtime provides the single Spring Boot `FlywayMigrationStrategy`. It:

1. Discovers every `SdkFlywayMigration` bean.
2. Topologically sorts the library migrations by their declared dependencies.
3. Runs each library's Flyway migrations against the application datasource.
4. Runs the application's Spring Boot Flyway migrations last.

This preserves the existing guarantee that SDK-managed database objects exist before application migrations run.

Independent libraries are ordered deterministically by their auto-configuration class name. Startup fails for duplicate
library definitions, missing dependencies, or dependency cycles.

After migrating each library, a shared Flyway callback grants
`"DTS JIT Access ccd DB Reader SC"` usage on the library schema and select access to its current and future tables. The
callback does nothing when the role is absent, allowing the same migrations to run in local and test environments.
