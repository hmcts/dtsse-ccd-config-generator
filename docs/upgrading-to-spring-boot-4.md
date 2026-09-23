# Upgrading a CCD service to Spring Boot 4

This guide supplements the official Spring documentation with lessons learned while upgrading
several reference CCD services.

The compatibility target for the CCD SDK is:

- Spring Boot 4 and Spring Framework 7;
- Java 21 for HMCTS services;
- Jackson 2 for application code and CCD wire contracts;
- no first-party use of Jackson 3 APIs.

Preserving the JSON contract is part of the upgrade. A build is not complete if it compiles and its
unit tests pass but callback data changes casing, nesting, enum values, or null handling.

## Official migration material

Read these before changing dependencies:

- [Spring Boot 4.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Boot 4.0 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)
- [Spring Boot JSON support](https://docs.spring.io/spring-boot/reference/features/json.html)
- [Spring Framework 7 upgrade notes](https://github.com/spring-projects/spring-framework/wiki/Upgrading-to-Spring-Framework-7.x)
- [`@MockitoBean` and `@MockitoSpyBean`](https://docs.spring.io/spring-framework/reference/testing/annotations/integration-spring/annotation-mockitobean.html)

Spring recommends upgrading to the latest Spring Boot 3.5 release and removing deprecated API use
before moving to Boot 4. Boot 4 is modular: application and test features that were previously
available transitively may now require a dedicated starter.

## Upgrade in small, auditable stages

Start from the service's current default branch.

1. Record the current JSON and generated CCD configuration.
2. Upgrade Spring Boot and the required HMCTS clients.
3. Make the minimum compilation and test-infrastructure changes.
4. Configure and verify Jackson 2 explicitly.
5. Run focused JSON round-trip tests.
6. Run the service's complete unit and integration checks.
7. Generate the CCD configuration again and compare it semantically with the baseline.

Keep dependency, framework API and JSON behaviour changes separate where practical. This makes a
contract regression much easier to locate.

## Boot 4 dependency and package changes

Typical dependency changes found in the upgraded services were:

| Before | Boot 4 |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` |
| `org.flywaydb:flyway-core` alone | `spring-boot-starter-flyway` |
| `spring-boot-starter-test` for MVC tests | `spring-boot-starter-webmvc-test` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |

Spring Boot's modularisation also moved classes into technology-specific packages. For example:

- Flyway auto-configuration moved from `org.springframework.boot.autoconfigure.flyway` to
  `org.springframework.boot.flyway.autoconfigure`;
- MVC test annotations such as `AutoConfigureMockMvc` moved under
  `org.springframework.boot.webmvc.test.autoconfigure`.

Do not solve missing classes by adding broad, old starters back to the classpath. Identify the Boot
4 module or test starter that owns the feature.

HMCTS client libraries also need Boot 4-compatible releases. Upgrade them individually and inspect
their dependency trees: a client may introduce Spring Boot's Jackson 3 starter even when the
application itself is configured for Jackson 2.

## Keep Jackson 2 explicitly

Spring Boot 4 prefers Jackson 3, whose Java packages use `tools.jackson.*`. Boot 4 also provides an
official Jackson 2 compatibility module. CCD services using this SDK must select that module and
keep their application and case-data code on `com.fasterxml.jackson.*`.

For a Spring MVC service, the core dependency setup is:

```groovy
dependencies {
  implementation('org.springframework.boot:spring-boot-starter-webmvc') {
    exclude group: 'org.springframework.boot', module: 'spring-boot-starter-jackson'
  }

  implementation 'org.springframework.boot:spring-boot-jackson2'
}
```

If a transitive HMCTS client brings Jackson 3 Boot modules into first-party application wiring,
exclude the modules at the dependency that introduces them:

```groovy
implementation('com.github.hmcts:some-client:VERSION') {
  exclude group: 'org.springframework.boot', module: 'spring-boot-starter-jackson'
  exclude group: 'org.springframework.boot', module: 'spring-boot-jackson'
}
```

Do not copy exclusions without checking the dependency graph. Third-party framework internals may
legitimately contain Jackson 3; the rule is that application and HMCTS-owned code must not depend on
its APIs.

An exclusion is not sufficient when a client library exposes `tools.jackson.*` in its own public
classes, bean methods or model annotations. In that case the application still needs the Jackson 3
types at runtime. Prefer a Boot 4-compatible Jackson 2 release when one exists. Where no such release
exists, keep the exception narrow: isolate the Jackson 3 mapper required by that client, keep the
application's primary HTTP mapper on Jackson 2, allow only exact source files, classes and dependency
versions in the compatibility guard, and retain focused wire-contract tests.

Select Jackson 2 for MVC and move the old Jackson properties beneath `spring.jackson2`:

```yaml
spring:
  http:
    converters:
      preferred-json-mapper: jackson2
  jackson2:
    deserialization:
      fail-on-null-for-primitives: false
```

The final property is an example. Preserve the service's existing value rather than copying it
blindly. The same applies to property inclusion, enum handling, date formats and naming strategies:
migrate the existing contract; do not choose new defaults during the framework upgrade.

For Lombok `@Jacksonized` classes, make the generated metadata explicitly target Jackson 2:

```properties
lombok.jacksonized.jacksonVersion += 2
```

Do not add `lombok.jacksonized.jacksonVersion += 3`, migrate imports to `tools.jackson.*`, or use the
Jackson 3 `ObjectMapper` in new application code.

## Verify the mapper used by the running application

A Jackson 2 dependency in `build.gradle` does not prove that Spring MVC selected it. Add a focused
integration test that verifies:

- the injected mapper is `com.fasterxml.jackson.databind.ObjectMapper`;
- the first Jackson MVC converter is `MappingJackson2HttpMessageConverter`;
- that converter uses the application's mapper;
- representative CCD values retain their exact property names.

This catches an accidental Jackson 3 converter or a second mapper that is configured differently
from the mapper used by callbacks.

The SDK's compatibility checks also run as part of `check`:

```shell
./gradlew jackson2CompatibilityGuard jackson2ClasspathGuard
```

The source guard rejects application references to `tools.jackson.*`. The classpath guard scans
compiled first-party code and dependencies while reporting, but not automatically failing on,
Jackson 3 used internally by third-party libraries. See [Jackson 2 classpath compatibility
guard](../classpathguard.md) for reports and exception rules.

## Nested `@JsonUnwrapped` data-loss risk

Successful serialisation does not prove successful deserialisation. Any case model that combines
`@JsonUnwrapped` with further nested complex types needs explicit round-trip coverage.

For example, a model may contain a prefixed unwrapped object:

```java
@JsonUnwrapped(prefix = "primary")
private PartyDetails partyDetails;
```

That object may contain another complex object such as an address, document, dynamic list or
organisation. A callback then has multiple mapping layers:

```text
CaseData -> @JsonUnwrapped PartyDetails -> nested CCD complex type
```

During an upgrade, the model may write apparently correct JSON but silently lose nested values when
the same payload is read back. A semantic or round-trip failure can therefore look like this:

```text
primaryAddress
expected: {"AddressLine1":"1 Example Street","PostCode":"AB1 2CD"}
actual:   {}
```

Nested `@JsonUnwrapped` deserialisation has
[long-standing Jackson limitations](https://github.com/FasterXML/jackson-databind/issues/1646).
Creator and property names are also case-sensitive: a constructor expecting `PropertyId` does not
match a wire field named `PropertyID`. Do not assume that setters, a no-args constructor or correct
serialised output guarantee a round trip.

The safe response is not to rename the JSON or weaken the assertion. Preserve the established wire
contract. Where normal Jackson binding cannot reliably reconstruct an affected type, register a
small Jackson 2 deserialiser that reads the existing CCD field names explicitly and distinguishes
missing values from JSON `null`. Add focused tests that serialise and deserialise representative
values and assert every nested property.

The SDK now auto-configures `UnwrappedPrefixModule` on every Jackson 2 `ObjectMapper` bean. It
keeps the unwrap prefix off nested values, whether the unwrapped object is built through setters or
through a creator such as a Lombok `@Builder @Jacksonized` class. Mappers a service creates outside
the Spring context, with `new ObjectMapper()` or a builder in a static field, do not get it and
should not be used for case data. The failure is
[jackson-databind #3178](https://github.com/FasterXML/jackson-databind/issues/3178). It applies to
jackson-databind 2.19 and later, which includes the versions Spring Boot 3.5 manages, so services
still on Boot 3 need the SDK release containing the module as well. Jackson 3.2 fixes it; Jackson 2
does not.

When a service uses `@JsonUnwrapped`, add round-trip tests at the highest model level that owns the
annotation. A unit test for the nested type alone may pass while the complete case-data path loses
values.

For a worked example of preserving an existing CCD contract with focused Jackson 2 deserialisers and
round-trip tests, see the
[NFDiv Spring Boot 4 fixture branch](https://github.com/hmcts/nfdiv-case-api/tree/sb4-from-latest-master).
The example is linked to demonstrate the mitigation pattern; the underlying risk applies to any
service with nested or unwrapped JSON models.

## Other problems found in the service upgrades

### Spring test mocks

Replace removed Boot Mockito annotations with Spring Framework's `@MockitoBean` or
`@MockitoSpyBean`. In a Spring integration test, a plain Mockito `@Mock` does not replace the bean in
the application context. Similarly, initialise an `ArgumentCaptor` explicitly if the test no longer
has a Mockito extension that processes `@Captor`.

### Custom HTTP message converters

Services that accept vendor-specific JSON media types may need an explicit
`MappingJackson2HttpMessageConverter` backed by the application's Jackson 2 mapper. Do not create an
unconfigured mapper inside the converter, as it can change casing, date and null behaviour.

### Gradle source-set dependencies

With newer Gradle and Boot plugins, using the whole test runtime classpath as an implementation
dependency can introduce unwanted task relationships. The service upgrades used the test output for
compilation and the runtime classpath only at runtime:

```groovy
integrationTestImplementation sourceSets.test.output
integrationTestRuntimeOnly sourceSets.test.runtimeClasspath
```

Apply the equivalent split to other custom test source sets when Gradle reports circular or implicit
task dependencies.

### Test JVM memory can disguise the real failure

One reference service completed most of its integration suite and then exhausted the Gradle test
worker's `512m` heap. The final failures appeared to be Spring `ApplicationContext` errors, but the
earlier log contained `Java heap space`. The narrow fix was applied to the integration task only:

```groovy
tasks.register('integration', Test) {
  maxHeapSize = '1g'
}
```

When CI fails late in a large suite, inspect the complete log before changing application wiring.

### Auto-configuration collisions

Updated HMCTS clients can add auto-configuration that the service previously created itself. If a
duplicate bean or unexpected client appears, identify the auto-configuration and decide which owner
is correct. Exclude a specific auto-configuration only when the service intentionally supplies the
same integration; do not disable broad auto-configuration packages.

## Contract-preservation tests

At minimum, cover the following with representative case data:

- camel case, snake case and CCD's upper-camel field names;
- `@JsonProperty`, `@JsonNaming` and `@JsonUnwrapped` fields;
- nested SDK complex types;
- enum wire values where `toString()` differs from `name()`;
- absent fields versus explicit JSON `null`;
- primitive and boxed null handling;
- dates and times, including fractional seconds;
- Lombok builders and `@Jacksonized` models;
- callback request and response payloads;
- generated CCD definitions.

Prefer assertions against the full JSON tree or a committed payload over checking only selected Java
fields. Always test both directions:

```text
known JSON -> model -> JSON
model -> JSON -> model
```

Run semantic configuration comparisons as well as Java tests. They catch removed fields, changed
field types, casing changes and ordering-independent structural differences that compilation cannot.

## Completion checklist

- [ ] Started from the latest service default branch.
- [ ] Read the official Boot 4 migration guide and reviewed removed deprecations.
- [ ] Replaced old broad starters with the required Boot 4 application and test starters.
- [ ] Upgraded Spring Cloud and HMCTS clients to Boot 4-compatible releases.
- [ ] Added `spring-boot-jackson2` and selected `jackson2` for each relevant mapper.
- [ ] Moved application settings from `spring.jackson` to `spring.jackson2` without changing values.
- [ ] Kept application imports on `com.fasterxml.jackson.*`.
- [ ] Configured Lombok `@Jacksonized` generation for Jackson 2.
- [ ] Ran `jackson2CompatibilityGuard` and `jackson2ClasspathGuard`.
- [ ] Verified the mapper and HTTP converter used by the running application.
- [ ] Added nested `@JsonUnwrapped` and SDK complex-type round-trip tests.
- [ ] Checked casing, enums, dates, missing fields and explicit nulls.
- [ ] Ran the complete unit and integration test suites.
- [ ] Regenerated CCD definitions and completed a semantic comparison against the baseline.
