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
