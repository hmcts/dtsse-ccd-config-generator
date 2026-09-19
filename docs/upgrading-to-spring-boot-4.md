# Upgrading a service to the Spring Boot 4 SDK

This SDK release moves to Spring Boot 4, Spring Framework 7 and Jackson 3.
The SDK's own case-data handling is insulated from the Jackson 3 default changes,
but your service's global `ObjectMapper` and any Jackson 2 code on your case model are not.

## 1. Build changes

| Before | After |
|---|---|
| `org.springframework.boot` plugin 3.x | `4.0.x` (the SDK BOM is aligned to 4.0.8) |
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `org.flywaydb:flyway-core` | `spring-boot-starter-flyway` |
| `org.testcontainers:junit-jupiter` / `postgresql` | `testcontainers-junit-jupiter` / `testcontainers-postgresql` |
| `com.fasterxml.jackson.core:jackson-databind` | `tools.jackson.core:jackson-databind` |
| `idam-java-client` 3.x, `service-auth-provider-java-client` 5.x, `ccd-case-document-am-client` 1.x, `core-case-data-store-client` 5.x | 4.0.0, 6.1.2, 2.0.0, 6.1.0 |
| Spring Cloud 2025.0.x, spring-cloud-azure 6.x | 2025.1.x, 7.x |

Flyway auto-configuration classes moved from `org.springframework.boot.autoconfigure.flyway`
to `org.springframework.boot.flyway.autoconfigure`.

## 2. Jackson 3 defaults are different, and Boot 4 does not restore the old ones

Spring Boot 4 ships `spring.jackson.use-jackson2-defaults=false`. The global mapper therefore:

- no longer writes into `final` fields (Lombok `@Data @Builder` models with final fields silently lose every value)
- serialises and deserialises enums via `toString()` instead of `name()`
- rejects `null` for primitive fields instead of defaulting them
- sorts properties alphabetically

The SDK applies its own policy (Jackson 2 behaviour for the four points above, plus NON_NULL for
values and map entries) to every mapper it uses for case data: persistence, projection, decentralised
submit, legacy callbacks and JSON callbacks. You do not need to configure anything for those paths.

What the SDK cannot protect is anything serialised by *your* mapper: classic callback responses returned
through Spring MVC, your own Feign clients, and anything you serialise yourself. Set this unless you have
deliberately audited every enum and final field in your codebase:

```yaml
spring:
  jackson:
    use-jackson2-defaults: true
```

## 3. Jackson 2 annotations and serializers on the case model are ignored by the SDK

Jackson 3 reads `com.fasterxml.jackson.annotation.*` (`@JsonProperty`, `@JsonIgnore`, `@JsonCreator`)
but **not** `com.fasterxml.jackson.databind.annotation.*`. On your case model, migrate:

- `@JsonDeserialize` / `@JsonSerialize` / `@JsonPOJOBuilder` to `tools.jackson.databind.annotation.*`
- custom serializers from `JsonSerializer` to `tools.jackson.databind.ValueSerializer`, and deserializers to
  `tools.jackson.databind.ValueDeserializer` / `StdDeserializer` (`SerializerProvider` becomes
  `SerializationContext`; `IOException` becomes `JacksonException`)
- `lombok.config`: add `lombok.jacksonized.jacksonVersion += 3` so `@Jacksonized` emits Jackson 3 annotations
- `@JsonComponent` to `@JacksonComponent`

A Jackson 2 annotation on a case-model field does not fail; it is silently skipped, so the field is
written with default handling. Grep for `com.fasterxml.jackson.databind` in your model package.

The SDK's `LocalDateTimeDeserializer` now accepts any ISO local date-time (optional seconds, up to nine
fraction digits). `ChangeOrganisationRequest` and `PreviousOrganisation` use it instead of the jsr310 one.

## 4. Do not run Jackson 2 and Jackson 3 side by side

Boot 4 lets you keep Jackson 2 for your own controllers via `spring-boot-jackson2` and
`spring.http.converters.preferred-json-mapper=jackson2`. Do not do this.

The SDK reads your case model with Jackson 3 no matter what your MVC layer uses, so the model migration in
section 3 cannot be deferred.

Migrate the whole service in one step: remove `spring-boot-jackson2` and the `preferred-json-mapper`
setting, migrate the model and any custom serializers, and treat any remaining
`com.fasterxml.jackson.databind` import as a defect.

## 5. Common API renames

| Jackson 2 | Jackson 3 |
|---|---|
| `mapper.copy().configure(...)` | `mapper.rebuild().enable(...).build()` |
| `JsonParser.Feature.X` | `StreamReadFeature.X` |
| `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` | `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` |
| `node.fields()` | `node.properties()` (or `node.values()`) |
| `JsonProcessingException` | `JacksonException` |
| `MappingJackson2HttpMessageConverter` | `JacksonJsonHttpMessageConverter` |
| `MappingJackson2MessageConverter` (JMS) | `JacksonJsonMessageConverter` |
| Feign `ObjectFactory<HttpMessageConverters>` | `ObjectProvider<FeignHttpMessageConverters>` |

## 6. Generated definitions

Generated CCD definition JSON is byte-for-byte unchanged. If regenerating produces a large diff,
something in your Jackson configuration is leaking into the generator; raise it with the SDK team.

## Checklist

- [ ] Build files updated per section 1
- [ ] `spring.jackson.use-jackson2-defaults=true` set, or every enum and final field audited
- [ ] No `com.fasterxml.jackson.databind` imports left in the case model
- [ ] `spring-boot-jackson2` and `preferred-json-mapper` removed
- [ ] `lombok.jacksonized.jacksonVersion += 3` in `lombok.config`
- [ ] Regenerated definitions show no diff
