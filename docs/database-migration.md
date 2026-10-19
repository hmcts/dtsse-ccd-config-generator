# Migrating the database in the pipeline

By default, a decentralised service runs its Flyway migrations, the SDK libraries' and then its own, when it starts.
That means the service has to connect as a login that can change the schema.

To have the service connect as a login without DDL rights instead, migrate in the pipeline:

1. Turn on the CNP pipeline's Flyway migration step with `enableDbMigration('<vault name>')` in the `withPipeline`
   block. See [Application specific infrastructure](https://github.com/hmcts/cnp-jenkins-library#application-specific-infrastructure)
   in the cnp-jenkins-library README.
2. Put the schema owner's credentials in the `<vault name>-<env>` vault, or the vault your terraform outputs as
   `vaultName`. The step reads `<component>-POSTGRES-HOST`, `-PORT`,
   `-DATABASE`, `-USER` and `-PASS` ([`GradleBuilder.dbMigrate`](https://github.com/hmcts/cnp-jenkins-library/blob/master/src/uk/gov/hmcts/contino/GradleBuilder.groovy)).
3. Deploy the service with `spring.flyway.enabled=false` and a runtime login that has DML rights on the application's
   and the SDK's schemas.

The step runs `./gradlew migratePostgresDatabase`. The CCD SDK Gradle plugin provides that task, so the SDK
libraries' migrations run first, as they would at startup. An application that defines its own
`migratePostgresDatabase` task keeps it. See the [SDK README](../sdk/README.md#migrating-outside-the-application) for
what the task does and doesn't load.
