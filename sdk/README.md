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

## Devtools and new libraries

When adding a library that shares SDK types, include its JAR in the Spring Boot devtools restart classloader.
Add `src/main/resources/META-INF/spring-devtools.properties` to the new library with a unique include key,
using its published artifact name in the pattern:

```properties
restart.include.documents=/documents.+\\.jar
```

`bootWithCCD` uses devtools, and the config generator and decentralised runtime already load in the restart
classloader. Without a matching include, a new library JAR stays in the base classloader. The two loaders can
load separate copies of `SdkFlywayMigration`, so the coordinator cannot discover the new library's migration
beans even though the application starts successfully. Other shared SDK types can have the same problem.

Verify new libraries with packaged JARs and the devtools restart classloader, including a fresh database and
restarts. Ordinary Spring integration tests use a single classloader and will not catch this problem.
`DocweaveRestartIntegrationTest` demonstrates this regression coverage.

Declare migration dependencies in `SdkFlywayMigration`; the `before` and `after` attributes on
`@AutoConfiguration` do not control migration execution order and are not needed just to register a library's migrations.

## Migration execution

The decentralised runtime provides the single Spring Boot `FlywayMigrationStrategy`. It:

1. Discovers every `SdkFlywayMigration` bean.
2. Topologically sorts the library migrations by their declared dependencies.
3. Runs each library's Flyway migrations against the application datasource.
4. Runs the application's Spring Boot Flyway migrations last.

This preserves the existing guarantee that SDK-managed database objects exist before application migrations run.

Independent libraries are ordered deterministically by their auto-configuration class name. Startup fails for duplicate
library definitions, missing dependencies, or dependency cycles.

After migrating each library for the first time, a shared repeatable migration grants the role configured by
`ccd.sdk.flyway.reader-role` usage on the library schema and select access to its existing tables. It also configures
default privileges so tables created by later migrations inherit select access. No grants are applied when the property
is unset. The configured database role must exist before the library's migrations run.

```yaml
ccd:
  sdk:
    flyway:
      # reader-role: DTS JIT Access et DB Reader ST
```
