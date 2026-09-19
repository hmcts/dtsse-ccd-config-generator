# Guide: Spring Boot 4 and Jackson 3 CCD data integrity

Spring Boot 4 and Jackson 3 present a material risk to CCD data integrity.

Code that builds and works under Spring Boot 3 can continue to build under Spring Boot 4 while:

- silently dropping populated CCD fields;
- changing persisted field names or enum values;
- converting empty values to `null`;
- adding computed properties to case data; or
- rejecting existing cases during deserialization.

The examples below are taken from the `master` branches of the repository's test projects. They are
not contrived examples: they are patterns already present in production-style CCD applications.

The examples were checked against:

- Adoption `master` at `602fc84d`;
- ET `master` at `7bc7f382`;
- No Fault Divorce `master` at `b00301aec`;
- PCS `master` at `ee868126`; and
- Special Tribunals `master` at `a8606ea0`.

## The namespace rule

Jackson 3 still understands the shared annotation package:

```java
com.fasterxml.jackson.annotation.*
```

Annotations such as `JsonProperty`, `JsonIgnore`, `JsonCreator`, `JsonValue`,
`JsonIgnoreProperties` and `JsonUnwrapped` are not inherently broken.

Jackson 3 does **not** use Jackson 2 databind classes and annotations:

```java
com.fasterxml.jackson.databind.*
com.fasterxml.jackson.databind.annotation.*
com.fasterxml.jackson.datatype.*
```

Their Jackson 3 equivalents are under:

```java
tools.jackson.databind.*
tools.jackson.databind.annotation.*
tools.jackson.datatype.*
```

This distinction is dangerous because Jackson 2 and Jackson 3 can be present on the same classpath.
The old annotation still compiles and remains visible at runtime, but a Jackson 3 mapper ignores it.

## 1. `JsonNaming` can silently drop every populated field

### Code on Adoption `master`

`test-projects/adoption-cos-api/src/main/java/uk/gov/hmcts/reform/adoption/adoptioncase/model/Applicant.java`

```java
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class Applicant {
    private String firstName;
    private String lastName;
    private String email;
    private LocalDate dateOfBirth;
}
```

The containing case model unwraps this object:

`test-projects/adoption-cos-api/src/main/java/uk/gov/hmcts/reform/adoption/adoptioncase/model/CaseData.java`

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseData {

    @JsonUnwrapped(prefix = "applicant1")
    private Applicant applicant1 = new Applicant();
}
```

### Why this is dangerous

Under Jackson 2, `UpperCamelCaseStrategy` maps `Applicant.email` to `Email`. Combined with the
unwrapped prefix, the stored CCD property is:

```json
{
  "applicant1Email": "person@test.local"
}
```

Jackson 3 ignores `com.fasterxml.jackson.databind.annotation.JsonNaming`, so it looks for:

```json
{
  "applicant1email": "person@test.local"
}
```

The real `applicant1Email` property is now unknown. The shared
`com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)` annotation still
works, so Jackson silently discards the populated value instead of reporting the mismatch.

The object is then serialized with the Jackson 3 name:

```json
{
  "applicant1email": null
}
```

This is proven rather than theoretical. In the Adoption AAT round trip:

- all 5,000 sampled cases changed;
- 187,853 populated scalar values became `null`; and
- field-name casing changed at 197 distinct paths.

### Other examples on `master`

The same dangerous pattern appears in:

```java
// PCS: TenancyLicenceDetails.java
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class TenancyLicenceDetails {
    private TenancyLicenceType typeOfTenancyLicence;
    private String detailsOfOtherTypeOfTenancyLicence;
    private LocalDate tenancyLicenceDate;
}
```

```java
// Special Tribunals: FurtherCICDetails.java
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class FurtherCICDetails {
    private SchemeCic schemeCic;
}
```

Any populated properties represented in stored JSON using the old naming strategy are at risk.

### Safe migration

For a model which may pass through both mapper generations, explicit shared annotations are the
least ambiguous fix:

```java
public class Applicant {

    @JsonProperty("Email")
    private String email;
}
```

For an unwrapped model, remember that the property names above are the names before the prefix is
applied. Do not guess the resulting CCD name; test it using existing stored JSON.

If the service has completely migrated to Jackson 3, the naming annotation can instead be migrated
to `tools.jackson.databind.annotation.JsonNaming`. Explicit `JsonProperty` names are still safer for
persistent data because they do not depend on mapper-wide naming behaviour.

## 2. A Jackson 2 custom deserializer is completely bypassed

### Code on No Fault Divorce `master`

`test-projects/nfdiv-case-api/src/main/java/uk/gov/hmcts/divorce/divorcecase/model/CaseDataOldDivorce.java`

```java
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = CaseDataOldDivorce.CaseDataOldDivorceDeserializer.class)
public class CaseDataOldDivorce {

    @JsonProperty("D8caseReference")
    private String d8caseReference;

    public static class CaseDataOldDivorceDeserializer
        extends JsonDeserializer<CaseDataOldDivorce> {

        @Override
        public CaseDataOldDivorce deserialize(
            JsonParser jsonParser,
            DeserializationContext context
        ) throws IOException {
            JsonNode rootNode = jsonParser.getCodec().readTree(jsonParser);
            CaseDataOldDivorce caseData = new CaseDataOldDivorce();
            caseData.setD8caseReference(
                rootNode.path("D8caseReference").asText(null)
            );
            return caseData;
        }
    }
}
```

### Why this is dangerous

Both `JsonDeserialize` and `JsonDeserializer` are Jackson 2 databind types. A Jackson 3 mapper does
not invoke this deserializer.

The default bean mapper takes over. Any transformation, compatibility lookup, nested extraction or
normalization performed by the custom deserializer is skipped. Because this class also ignores
unknown properties, nested legacy data that the deserializer previously recovered can disappear
without an exception.

### Safe migration

Port both parts:

- use `tools.jackson.databind.annotation.JsonDeserialize`;
- implement a Jackson 3 `tools.jackson.databind.ValueDeserializer` or
  `tools.jackson.databind.deser.std.StdDeserializer`;
- replace Jackson 2 parser, context, node and exception types with their Jackson 3 equivalents; and
- test the migrated deserializer against real legacy case JSON.

Migrating only the annotation or only the implementation is insufficient.

## 3. Lombok `@Jacksonized` can generate annotations for the wrong Jackson

### Code on ET `master`

`test-projects/et-ccd-callbacks/et-shared/src/main/java/uk/gov/hmcts/et/common/model/ccd/types/NoticeOfChangeAnswers.java`

```java
@Data
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NoticeOfChangeAnswers {

    @JsonProperty("respondentName")
    private final String respondentName;

    @JsonProperty("claimantFirstName")
    private final String claimantFirstName;

    @JsonProperty("claimantLastName")
    private final String claimantLastName;
}
```

ET's `lombok.config` on `master` contains:

```properties
config.stopBubbling = true
lombok.addLombokGeneratedAnnotation = true
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
```

It does not select Jackson 3 for `@Jacksonized`.

### Why this is dangerous

`@Jacksonized` generates databind annotations such as `JsonDeserialize` and `JsonPOJOBuilder`.
Without Jackson 3-aware Lombok configuration, those generated annotations use the Jackson 2
`com.fasterxml.jackson.databind.annotation` namespace.

Jackson 3 ignores the generated builder metadata. This class has final fields and no ordinary no-arg
construction path, so deserialization can fail completely rather than merely losing one field.

When ET's models were forced through the Jackson 3 SDK callback path, this category produced
thousands of deserialization failures. ET's existing Jackson 2 HTTP path hides the problem; it does
not make these models Jackson 3 compatible.

### Safe migration

Use a recent Lombok version and add:

```properties
lombok.jacksonized.jacksonVersion += 3
```

Then inspect generated code or run a real round trip to prove the builder uses
`tools.jackson.databind.annotation`.

Do not assume that adding `@Jacksonized` is itself a fix. With the wrong Lombok configuration it
cements the Jackson 2 dependency.

## 4. Jackson 2 field serializers are silently ignored

### Code on ET `master`

`test-projects/et-ccd-callbacks/et-shared/src/main/java/uk/gov/hmcts/et/common/model/ccd/types/ChangeOrganisationRequest.java`

```java
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChangeOrganisationRequest {

    @JsonProperty("RequestTimestamp")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime requestTimestamp;
}
```

### Why this is dangerous

The Jackson 3 mapper ignores this `JsonSerialize`. It applies its own `LocalDateTime` handling
instead.

The result may still look like a valid date-time, which makes this especially easy to miss, but the
stored representation can change in its use of seconds, fractional precision or another formatting
detail. A later callback can therefore rewrite a case even though the application did not change
the timestamp.

### Safe migration

Migrate the annotation and serializer to Jackson 3:

```java
import tools.jackson.databind.annotation.JsonSerialize;
```

Use a Jackson 3 serializer implementation and verify the exact JSON string against existing CCD
values. Do not test only whether the value can be parsed.

## 5. Jackson 2 `JsonNode` is a different Java type

### Code on ET `master`

`test-projects/et-ccd-callbacks/et-shared/src/main/java/uk/gov/hmcts/ecm/common/model/ccd/CaseDataContent.java`

```java
import com.fasterxml.jackson.databind.JsonNode;

@Data
@Builder
public class CaseDataContent {
    private Event event;
    private Map<String, JsonNode> data;

    @JsonProperty("security_classification")
    private Map<String, JsonNode> securityClassification;
}
```

### Why this is dangerous

`com.fasterxml.jackson.databind.JsonNode` and `tools.jackson.databind.JsonNode` are unrelated Java
types. They cannot be passed interchangeably between Jackson 2 and Jackson 3 code.

Keeping this DTO while introducing a Jackson 3 mapper creates an architectural boundary inside the
case-data payload. Code is then tempted to convert through strings, raw maps or unchecked casts,
each of which can change numbers, nulls, object ordering or custom node types.

### Safe migration

Migrate the DTO and every caller to the Jackson 3 node type in one change. Do not expose both node
types from the same case-data API.

## 6. Existing `null` values can break primitive fields

### Code on No Fault Divorce `master`

`test-projects/nfdiv-case-api/src/main/java/uk/gov/hmcts/divorce/divorcecase/model/ConditionalOrder.java`

```java
@CCD(label = "How many times have we tried the remind applicant apply for CO cron")
private int cronRetriesRemindApplicantApplyCo;
```

Another example is:

`test-projects/nfdiv-case-api/src/main/java/uk/gov/hmcts/divorce/divorcecase/model/RetiredFields.java`

```java
@CCD(label = "Case data version")
private int dataVersion;
```

### Why this is dangerous

Jackson 2 commonly accepted a stored JSON `null` for a primitive and assigned the Java default:

```json
{
  "cronRetriesRemindApplicantApplyCo": null
}
```

became:

```java
cronRetriesRemindApplicantApplyCo == 0
```

Jackson 3 rejects null primitives by default. One historic case containing `null` can therefore make
an event or callback fail before application code runs.

Changing the mapper to accept `null` restores availability but can introduce another integrity
problem: serializing the Java default can turn a stored `null` or absent property into an explicit
zero.

### Safe migration

If null and zero have different meanings, use `Integer`, not `int`.

If they are intentionally equivalent, configure the relevant mapper explicitly and verify whether
default values should be omitted on output. The SDK's dedicated case-data mapper restores the
Jackson 2 null-primitive behaviour, but application-owned Spring MVC mappers and manually-created
mappers need their own audit.

## 7. Final builder fields can fail or deserialize as defaults

### Code on PCS `master`

`test-projects/pcs-api/src/main/java/uk/gov/hmcts/reform/pcs/camunda/CamundaRequestTaskData.java`

```java
@Data
@Builder
@AllArgsConstructor
public class CamundaRequestTaskData {
    private final Action action;
    private final long caseReference;
    private final TaskType taskType;
    private final String taskDescription;
    private final UUID idempotencyKey;
}
```

ET has a similar CCD-package DTO:

```java
@Builder
@Data
public class UploadedDocument {
    private final Resource content;
    private final String name;
    private final String contentType;
}
```

### Why this is dangerous

Jackson 3 does not populate final fields using the old Jackson 2 default behaviour. These classes
also lack an ordinary no-arg mutable construction path.

Depending on available constructor metadata, a Jackson 3 mapper can either fail to construct the
object or produce null/default field values. `@Builder` alone does not tell Jackson how to use the
builder.

This example is not a persisted CCD aggregate, but it demonstrates why the upgrade must cover
application-owned HTTP, queue and client DTOs as well as the SDK's case-data mapper.

### Safe migration

Choose one explicit construction strategy:

- a correctly configured Jackson 3 `@Jacksonized` builder;
- an explicit shared `@JsonCreator` constructor with `@JsonProperty` parameters; or
- a no-arg mutable bean where immutability is not required.

Do not rely on final-field mutation.

## 8. Enum values can change from `name()` to `toString()`

### Code on ET `master`

`test-projects/et-ccd-callbacks/et-shared/src/main/java/uk/gov/hmcts/et/common/model/hmc/ListingReasonCode.java`

```java
public enum ListingReasonCode {
    NO_MAPPING_AVAILABLE("no-mapping-available"),
    USER_ADDED_COMMENTS("user-added-comments");

    private final String label;

    @Override
    public String toString() {
        return label;
    }
}
```

### Why this is dangerous

Under the previous default, this enum is written using `name()`:

```json
"NO_MAPPING_AVAILABLE"
```

Under the Jackson 3 default it can be written using `toString()`:

```json
"no-mapping-available"
```

Both strings look deliberate and valid, so representation drift can pass ordinary tests. Existing
consumers, searches or persisted values may require one exact representation.

### Safe migration

Do not allow mapper defaults to define persistent enum values. Use an explicit shared `@JsonValue`
for writing and a matching `@JsonCreator` for reading, or configure the mapper to preserve `name()`
semantics.

`com.fasterxml.jackson.annotation.JsonValue` itself is not a Jackson 2 databind annotation and is
still supported by Jackson 3. For example, Adoption's `UserRole` explicitly serializes its `role`
field. The risk is relying on changed defaults, not the `com.fasterxml.jackson.annotation` package.

## 9. Computed getters can become persisted case fields

### Code on Special Tribunals `master`

`test-projects/sptribs-case-api/src/main/java/uk/gov/hmcts/sptribs/ciccase/model/CaseData.java`

```java
public String getFirstHearingDate() {
    Listing nextListing = getNextListedHearing();
    if (nextListing != null && nextListing.getDate() != null) {
        return DateTimeFormatter.ofPattern("dd MMM yyyy", UK)
            .format(nextListing.getDate());
    }
    return "";
}

public String getHearingVenueName() {
    Listing nextListing = getNextListedHearing();
    if (nextListing != null) {
        return nextListing.getHearingVenueNameAndAddress();
    }
    return "";
}

public String getClosedDayCount() {
    if (closureDate != null) {
        long days = ChronoUnit.DAYS.between(closureDate, LocalDate.now());
        return "This case has been closed for %d days".formatted(days);
    }
    return "";
}
```

The same class correctly protects other helpers:

```java
@JsonIgnore
public boolean isBundleOrderEnabled() {
    return YesNo.YES.equals(this.newBundleOrderEnabled);
}
```

### Why this is dangerous

Bean introspection treats public `getX()` methods as JSON properties. The unignored methods can add:

```json
{
  "firstHearingDate": "",
  "hearingVenueName": "",
  "closedDayCount": "This case has been closed for 14 days"
}
```

to serialized case data even though no stored fields exist for them.

`closedDayCount` is particularly dangerous because its value changes as time passes. Merely loading
and resubmitting an unchanged case can produce a different JSON document every day.

Empty-string getters are not protected by `NON_NULL`; `""` is not null.

### Safe migration

Annotate every non-persistent helper with the shared annotation:

```java
@JsonIgnore
public String getClosedDayCount() {
    // ...
}
```

Do not depend on a mapper's current visibility rules. A case-data model should make the distinction
between persistent properties and helper methods explicit.

## 10. Jackson 2 modules and mix-ins do not configure Jackson 3

### Code on PCS `master`

`test-projects/pcs-api/src/main/java/uk/gov/hmcts/reform/pcs/config/JacksonConfiguration.java`

```java
public ObjectMapper draftCaseDataObjectMapper() {
    return JsonMapper.builder()
        .addMixIn(YesOrNo.class, YesOrNoMixin.class)
        .addMixIn(PCSCase.class, DraftCaseDataMixIn.class)
        .build();
}
```

`DraftCaseDataMixIn` prevents derived and internal properties from being persisted:

```java
public abstract class DraftCaseDataMixIn {

    @JsonIgnore
    private DashboardData dashboardData;

    @JsonIgnore
    private String claimantName;

    @JsonIgnore
    private String claimantContactEmail;
}
```

No Fault Divorce similarly registers Jackson 2 `SimpleModule` deserializers for `HasRole` and
`InternalHealth`.

### Why this is dangerous

A module, mix-in or customizer registered on a
`com.fasterxml.jackson.databind.ObjectMapper` has no effect on a
`tools.jackson.databind.ObjectMapper`.

The Jackson 2 mapper bean can remain present and correctly configured while a new Jackson 3 mapper
uses none of that policy. PCS can then expose fields that its draft mapper deliberately excluded or
change `YesOrNo` values. NFDIV can lose its interface deserializers. Manually-created mappers such as
`new ObjectMapper()` create the same problem because they bypass application-wide configuration.

This is easy to miss when tests inject the old primary mapper while a production path uses the new
one.

### Safe migration

Inventory every mapper bean, module, mix-in, builder customizer and direct `new ObjectMapper()`.
Port each registration to Jackson 3 and test the path that consumes that specific mapper.

Do not assume that Spring will copy configuration between mapper generations. Prefer one
application-wide serialization system, with separately named mappers only where their boundaries
and tests are explicit.

## 11. Running Jackson 2 and Jackson 3 side by side hides defects

A Spring Boot 4 service can retain Jackson 2 for selected HTTP paths while the CCD SDK uses Jackson
3 for case data.

This can make all ordinary controller tests pass:

- the Jackson 2 MVC mapper honours `com.fasterxml.jackson.databind.annotation.JsonNaming`;
- the Jackson 2 mapper understands Lombok's Jackson 2 `@Jacksonized` metadata; and
- Jackson 2 custom serializers continue to run.

The same model then fails or loses data when it reaches an SDK callback, projection or persistence
path using Jackson 3.

ET demonstrates this trap. Its models work through its Jackson 2 HTTP mapper, but forcing the same
models through the Jackson 3 callback mapper exposed thousands of builder/deserialization failures.

Do not treat a passing Jackson 2 controller test as evidence that a case model is Jackson 3 safe.
Migrate the service as one serialization system rather than preserving mapper islands.

## 12. Empty, absent and null values are not interchangeable

The Adoption AAT round trip also found:

- 39,984 empty arrays changed to `null`;
- 27,887 empty strings changed to `null`;
- 10,822 empty objects changed to `null`;
- 30,343 populated structures changed to `null`; and
- 61,913 new null-valued fields were emitted.

These differences may not break Java deserialization, but they can change:

- CCD conditional display expressions;
- whether a user is asked a question again;
- downstream callback logic;
- Elasticsearch queries;
- document generation; and
- whether an update overwrites an existing value.

A test that compares only non-null Java fields will miss these changes.

## Required upgrade process

### 1. Search the case model and all serialization DTOs

Search for:

```text
com.fasterxml.jackson.databind
com.fasterxml.jackson.datatype
@Jacksonized
private final
private int
private boolean
toString()
public get
public is
new ObjectMapper
```

Do not restrict the search to the root case-data class. Inspect every nested complex type, callback
request/response, Feign DTO, queue message and custom mapper.

### 2. Fail static compatibility checks

Application source should not use Jackson 2 core, databind, datatype, module or legacy Spring
Jackson APIs after migration.

Shared `com.fasterxml.jackson.annotation.*` imports are allowed. Treating every `com.fasterxml`
import as invalid would incorrectly reject the annotations deliberately shared by Jackson 2 and 3.

Static checks are necessary but cannot prove data integrity. They cannot detect changed defaults,
computed getters, constructor side effects or empty/null normalization.

### 3. Round trip real stored data through the real application path

For each CCD case type:

1. Obtain a state-diverse sample of existing cases.
2. Send each case through the same HTTP callback/controller path used in production.
3. Deserialize it into the application's real domain model.
4. Serialize the resulting response using the actual production mapper.
5. Store the output separately using the case reference as the filename.
6. Compare input and output semantically.

Do not test only:

```java
mapper.writeValueAsString(mapper.readValue(json, CaseData.class));
```

That bypasses Spring HTTP converters, callback wrappers, mapper customizers and application-specific
configuration.

### 4. Classify every difference

At minimum, report:

- populated value to `null`;
- populated value removed;
- property name changed;
- enum value changed;
- number or date representation changed;
- absent to `null`;
- `null` to absent;
- empty string, array or object to `null`;
- new computed property;
- collection order changed; and
- hard deserialization failure.

Do not approve a large diff merely because both documents are valid JSON.

### 5. Require explicit approval for intentional normalization

The default expectation for a no-op round trip is semantic equivalence. If the application intends
to normalize a value, document that exact path and transformation. Everything else is a migration
defect.

## Release checklist

- [ ] No Jackson 2 core, databind, datatype or module imports remain in application source.
- [ ] Every `@Jacksonized` model emits Jackson 3 builder annotations.
- [ ] Every custom serializer and deserializer has been ported and tested with stored values.
- [ ] Every mapper module, mix-in and customizer has been ported to Jackson 3.
- [ ] Persistent names are explicit where naming strategies were previously used.
- [ ] Enum storage does not depend on mapper defaults.
- [ ] Historic nulls have been tested against every primitive case-data field.
- [ ] Final fields have an explicit supported construction path.
- [ ] Non-persistent getters and `is...` helpers are annotated with `@JsonIgnore`.
- [ ] Jackson 2 and Jackson 3 are not used for different paths through the same model.
- [ ] Every populated case type has passed an HTTP-level AAT-data round trip.
- [ ] Every input case has one valid output file with the same case reference.
- [ ] Every semantic difference has been reviewed rather than hidden by snapshot replacement.
