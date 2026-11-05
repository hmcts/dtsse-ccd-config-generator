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

New libraries sharing SDK types must add `src/main/resources/META-INF/spring-devtools.properties`
with a unique key and their artifact name:

```properties
restart.include.documents=/documents.+\\.jar
```

This keeps them in the SDK's restart classloader; otherwise `bootWithCCD` can silently skip their migrations.

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

## Migrating outside the application

The CCD SDK Gradle plugin adds a `migratePostgresDatabase` task, which needs `decentralised-runtime` on the classpath.
It runs the same library-then-application migrations in a process of its own, and then exits. Only the datasource,
Flyway and the libraries' migration registrations are loaded. The CNP pipeline's `enableDbMigration` runs this task,
passing `-Pdburl`, `-Pflyway.user` and `-Pflyway.password`. Without them, the application's `spring.datasource`
configuration applies.

So the pipeline can migrate as the schema owner, while the deployed application connects as a role without DDL and sets
`spring.flyway.enabled=false`.

Limits:

- The task is added after the build script is evaluated. An application that already defines a
  `migratePostgresDatabase` task keeps its own.
- The application's configuration is loaded, so its `spring.config.import` entries and active profiles must work where
  the task runs.
- The application's own beans are not loaded. Flyway callbacks, `JavaMigration` beans and a custom `DataSource` bean do
  not apply. Java migrations in Flyway's locations still run.

A library's migration auto-configuration must also be listed in
`META-INF/spring/uk.gov.hmcts.ccd.sdk.config.ImportSdkFlywayMigrations.imports` for this task to run it. The task
loads everything that configuration declares.
