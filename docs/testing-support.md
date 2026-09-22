# CCD SDK testing support

`ccd-sdk-test-support` runs configured CCD events against the SDK submission runtime and a disposable PostgreSQL database. Tests can seed or create a case, submit an event, run its `CaseView`, and inspect both the projected result and the persisted record.

Add the module to an application that uses the SDK BOM:

```groovy
dependencies {
  testImplementation 'com.github.hmcts:ccd-sdk-test-support'
}
```

## Focused event tests

Use `@CcdSdkTest` to start only the application components needed for the event round trip. Provide the real base `CCDConfig`, the event config, and the `CaseView`. Include application Jackson configuration when the case model needs it. If the view uses JPA, specify a repository and entity from each package it needs:

```java
@CcdSdkTest(
    components = {BaseConfig.class, ReadOnlyEvent.class, MyCaseView.class},
    repositories = CaseLookupRepository.class,
    entities = CaseLookup.class
)
class ReadOnlyEventTest {
    @Autowired
    CcdEventTestSupport<MyCase, MyState> events;
}
```

Omit `repositories` and `entities` when the selected view does not use JPA. The focused context resolves the selected configs with `CCDDefinitionGenerator.loadConfigs()`, so case type grouping and validation use the production path. It includes the SDK's case data mapper, JSON callback bridge, MVC, JPA and database migrations. It does not scan the rest of the application. Callback URL validation for unrelated JSON events is skipped; a JSON callback that the test submits still needs its route or external URL to work.

The SDK's [focused integration test](../sdk/ccd-sdk-test-support/src/test/java/uk/gov/hmcts/ccd/sdk/testing/CcdSdkTestIntegrationTest.java) is an executable example. Its event returns a default submit response, while its view adds a projection:

```java
long reference = events.seed(TestState.Open, new TestCase("stored"));
var before = events.snapshot(reference);

var result = events.event(reference, "readOnly", new TestCase("submitted"))
    .submitExpectingSuccess();

assertThat(result.projectedCase().value()).isEqualTo("stored (view)");
assertThat(result.rawData()).isEqualTo(before.rawData());
assertThat(result.blobVersion()).isEqualTo(before.blobVersion());
assertThat(result.caseRevision()).isEqualTo(1);
```

`blobVersion()` reports `ccd.case_data.version`; it can also advance when state, TTL or classification changes. `caseRevision()` reports the event revision. Comparing both shows whether a submission wrote the blob while still adding event history.

## Cases, actors and results

Inject `CcdEventTestSupport<Case, State>` with concrete case and enum state types. When those types resolve to one case type, call `events.seed(...)`, `events.event(...)`, or `events.create(...)` directly. For multiple case types, select one with `events.forCaseType(caseTypeId)` first.

`seed(state, data)` allocates a 16-digit reference and inserts a fixture without event history. Use `seedCase(state, data)` to set a reference, supplementary data, `TestClassification`, or TTL before calling `insert()`. Use `create(eventId, initialState, data).submitExpectingSuccess()` to exercise a configured creation event and write its history.

For an existing case, `event(reference, eventId, submittedData)` creates a request. Set an actor with `.as(actor)`, a start revision with `.atRevision(revision)`, or an idempotency key with `.withIdempotencyKey(key)` before submitting. Register an actor with `events.registerActor(new ActorDetails(...))`; registration returns the `Actor` handle. The helper checks the stored state against the event's allowed pre-states before submission.

`submit()` returns an `Accepted` or `Rejected` result. Use `submitExpectingSuccess()` or `submitExpectingErrors()` when the expected outcome is known. Creation has a separate request type and returns `CreationRejected` on validation failure. Results expose errors, warnings, and confirmation text directly. Existing-case results also expose typed state, classification, supplementary data, and `storedData()`.

On an accepted result, `storedData()` reads the saved blob as the typed case model, while `projectedCase()` includes the `CaseView` projection. `rawData()` returns the blob as JSON. `snapshot(reference)` reads the persisted blob, blob version, and case revision directly from `ccd.case_data`.

Do not put these tests in a `@Transactional` test method: the helper needs to observe the runtime's committed transaction. The default audit actor is an SDK test user. These tests exercise submission and projection; they do not simulate CCD event permissions, about-to-start, or page callbacks.

## Existing application context

Use `@CcdSdkPostgresTest` with an existing `@SpringBootTest` context when a test needs the whole application's wiring, including JSON definition loading and local callback routing. Use `@EnableCcdEventTesting` if that context already supplies a datasource. Both add the typed helper and submission runtime without replacing the application configuration. The SDK's [application-context integration test](../sdk/ccd-sdk-test-support/src/test/java/uk/gov/hmcts/ccd/sdk/testing/CcdEventTestSupportIntegrationTest.java) shows this path.

Run the module checks with `./gradlew -p sdk :ccd-sdk-test-support:check` from the repository root.
