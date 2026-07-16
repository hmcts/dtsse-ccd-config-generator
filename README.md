# CCD Config Generator ![Java CI](https://github.com/hmcts/ccd-config-generator/workflows/Java%20CI/badge.svg?branch=master) ![GitHub tag (latest SemVer)](https://img.shields.io/github/v/tag/hmcts/ccd-config-generator?label=release)

Write CCD configuration in Java.

### Table of contents
* [Why](#why)
* [Installation](#installation)
  + [Config generation](#config-generation)
* [Getting started](#getting-started)
  + [Setting up the case type](#setting-up-the-case-type)
  + [Setting up the model](#setting-up-the-model)
  + [Setting up case states](#setting-up-case-states)
  + [Setting up user roles](#setting-up-user-roles)
  + [Adding events](#adding-events)
  + [Configuring the work basket and search fields](#configuring-the-work-basket-and-search-fields)
  + [Adding tabs](#adding-tabs)
* [Permissions](#permissions)
  + [Events](#events)
  + [Fields](#fields)
  + [States](#states)
  + [Case type (shuttering)](#case-type--shuttering-)
* [Unwrapped types](#unwrapped-types)
  + [Permissions](#permissions-1)
  + [Lombok](#lombok)
  + [Jackson configuration](#jackson-configuration)
* [Customising the generated JSON](#customising-the-generated-json)
* [Reference projects](#reference-projects)
* [Where to get help](#where-to-get-help)
* [Contributing](#contributing)
  + [Local development](#local-development)

## Why

* Compile-time type checking & auto-refactoring for CCD configuration
* Auto-generation of CCD schema based on your existing Java domain model (CaseField, ComplexType, FixedList etc)
* Avoid common CCD configuration mistakes with a simplified API
* Your application's code as the single source of truth
* Less boilerplate code with inline event callbacks

[Video guide](https://www.youtube.com/watch?v=sJfVXC6ihJU&list=PLrBWj5Zm4IGFwqewMqrMwQOkhMMDd5Tu4)

## Requirements

* Gradle 7.3
* Java 21

## Installation

### Add HMCTS Azure Artifacts as a Gradle plugin repository

The plugin is hosted on [Azure Artifacts](https://hmcts.github.io/cloud-native-platform/common-pipeline/publishing-libraries/java.html) so you must add the following to your project's `settings.gradle`;

```gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url 'https://pkgs.dev.azure.com/hmcts/Artifacts/_packaging/hmcts-lib/maven/v1'
        }
    }
}
```

Add the plugin to your `build.gradle` file in the project containing your Java domain model:

```groovy
plugins {
  id 'hmcts.ccd.sdk' version '[latest version at top of page]'
}
```

And set the destination folder for the generated config

```groovy
ccd {
  configDir = file('build/ccd-definition')
}
```

For decentralised services you can opt in to specialised runtime features:

```groovy
ccd {
  decentralised = true
  runtimeIndexing = true // brings ccd-runtime-indexing into the main runtime classpath
}
```

When `runtimeIndexing` is enabled the decentralised indexer uses PostgreSQL `LISTEN`/`NOTIFY` for low latency indexing,
with `ccd.sdk.decentralised.poll-interval-ms` as a fallback.

Set `ELASTIC_SEARCH_HOSTS` to the Elasticsearch HTTP endpoint or a comma-separated list of endpoints. For example:
`ELASTIC_SEARCH_HOSTS=http://es-1:9200,http://es-2:9200`.

The indexer is enabled by default. To leave the runtime indexing library on the classpath while temporarily
disabling the decentralised Elasticsearch indexer, set
`CCD_SDK_DECENTRALISED_ES_INDEXER_ENABLED=false`.

### Config generation

The `generateCCDConfig` task generates the configuration in JSON format to the configured folder:

```shell
./gradlew generateCCDConfig
or
./gradlew gCC
```

If you need libraries that are only required when producing configuration artefacts, add them to the `configGeneration` configuration. These dependencies are available to `generateCCDConfig` without polluting your application's runtime classpath:

```groovy
dependencies {
  configGeneration 'com.example:ccd-config-support:1.2.3'
}
```

Once created it can be converted to an XLSX by using the [ccd-definition-processor](https://github.com/hmcts/ccd-definition-processor/):

```shell
docker run --pull always --user $UID --rm \
  -v build/ccd-definition:/tmp/ccd-definition \
  -v build/xslx:/tmp/ccd-definition.xlsx \
  hmctspublic.azurecr.io/ccd/definition-processor:latest \
  json2xlsx -D /tmp/ccd-definition -o /tmp/ccd-definition.xlsx

```
## Getting started

The generator is configured by providing one or more implementations of the [CCDConfig](https://github.com/hmcts/ccd-config-generator/blob/master/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/api/CCDConfig.java) interface.

### Setting up the case type

```java
@Component
public class MyConfig implements CCDConfig<CaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    builder.caseType("MY_CASE_TYPE", "My Case Type", "Case type description");
    builder.jurisdiction("MY_JURISDICTION", "Jurisdiction", "Jurisdiction description");
    builder.hmctsServiceId("ABA1");
    builder.setCallbackHost(System.getenv().getOrDefault("API_URL", "http://localhost:4013"));
  }

}
```

For decentralised services using their own database for data persistence,
`hmctsServiceId("ABA1")` sets supplementary data key `HMCTSServiceId` to 'ABA1' and indexes it into Elasticsearch
for global search.

`builder.enableForDeletion()` sets the CaseType sheet's `EnableForDeletion=Y`, and
`builder.jurisdictionShuttered()` sets the Jurisdiction sheet's `Shuttered=Y`. Neither is consumed
by CCD at runtime today; both are definition-time flags carried for tooling/migration parity. This
is unrelated to [shuttering](#Shuttering), which is the mechanism that actually restricts access.

`builder.printableDocumentsUrl(url)` sets the CaseType sheet's `PrintableDocumentsUrl` column, the
webhook the definition store calls to obtain a printable representation of a case. Omitted (the
default) leaves the column unset, matching output produced before this option existed.

The implementation of `CCDConfig` should reference three classes: one for the model, one for the states and one for the user roles. These are typically named: CaseData, State and UserRole.

### Setting up the model

The case fields and complex types are derived from a top level class, usually called `CaseData`.

This example has a single field `applicantName` and a label defined for that field:

```java
public class CaseData {

  @CCD(
    label = "Applicant name"
  )
  private String applicantName;
}
```

There are number of predefined types in CCD that are included in the library, such as a `YesOrNo` field:

```java
  @CCD(
    label = "They have agreed to receive notifications by email"
  )
  private YesOrNo agreedToReceiveEmails;
```

See [in-built types](https://github.com/hmcts/ccd-config-generator/tree/master/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/type) for a complete list.

`FieldType` also covers base types the definition-store importer accepts but that only appear via
`typeOverride`, such as `WaysToPay`, `CaseHistoryViewer`, `AddressUK`/`AddressGlobal`/`AddressGlobalUK`,
`DateTime`, `Number`, `Fee`, `Organisation`, `OrganisationPolicy` and `ChangeOrganisationRequest`.
`JudicialUser` is a predefined complex type (like `CaseLink` or `Document`) — reference
`uk.gov.hmcts.ccd.sdk.type.JudicialUser` as a field's Java type directly rather than using
`typeOverride`.

It is also possible to override the Java type for a CCD specific one. For example, a `String` that should be an `Email` type in CCD:

```java
  @CCD(
    label = "Applicant email address",
    typeOverride = Email
  )
  private String applicantEmail;
```

A common pattern is to use a Java enum to represent a fixed list

```java
  @CCD(
    label = "Application type",
    typeOverride = FixedList,
    typeParameterOverride = "ApplicationType"
  )
  private ApplicationType applicationType;
}
```

Where `ApplicationType` is an enum that implements `HasLabel`:

```java
public enum ApplicationType implements HasLabel {
  @JsonProperty("soleApplication")
  SOLE_APPLICATION("Sole Application"),

  @JsonProperty("jointApplication")
  JOINT_APPLICATION("Joint Application");

  private final String label;
```

It is possible to add your own Java models as complex types:

```java
  private Application application;
```

Note that the property definition doesn't always require the `@CCD` annotation. All fields in the CaseData class will be added to the definition.

```java
public class Application {
  @CCD(label = "Date submitted")
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
  private LocalDateTime dateSubmitted;
}
```

`LocalDateTime` classes are mapped to the CCD `DateTime` type so the complex type `Application` will have a single `DateTime` field.

### Generation-time environment gates

`@CCD(gate = "[!]ENV_VAR:value")` makes a field part of the generated definition only when an
environment predicate matches at the moment `generateCCDConfig` runs. It is the SDK counterpart of
the per-environment overlay files hand-written definitions activate by glob inclusion/exclusion: instead of
shipping a field in a `CaseField-prod.json` fragment, the field is annotated and the generator emits — or
omits — every row it owns according to whether the gate matches. The predicate grammar is identical to the
ccd-definition-converter's overlay condition, so a converted overlay suffix maps to the same expression:

```java
  @CCD(label = "A Judgments-Online field", gate = "CCD_DEF_JO:true")
  private String judgmentsOnlineField;
```

- `CCD_DEF_JO:true` — active when `CCD_DEF_JO` resolves to `true` (case-insensitively).
- `!CCD_DEF_ENV:prod` — active when `CCD_DEF_ENV` does *not* resolve to `prod`.

The variable is resolved from a system property first, then the process environment. When the gate is
inactive the gated field is dropped exactly like `@CCD(ignore = true)`: every row it owns — CaseField,
AuthorisationCaseField, CaseEventToFields placements, CaseTypeTab, WorkBasket/Search — vanishes, while
ungated fields regenerate byte-identically.

The same gate works on a **member of a complex type**, for members that exist only in a per-environment
overlay and so cannot be expressed by a field-level gate on the CaseData class:

```java
  @ComplexType(name = "GatedMemberComplex", generate = true)
  public class GatedMemberComplex {
    @CCD(label = "An always-present member")
    private String alwaysMember;

    @CCD(label = "A Judgments-Online member", gate = "CCD_DEF_JO:true")
    private GatedMemberNested gatedMember;
  }
```

When the gate is inactive the gated member vanishes from that type's `ComplexTypes` rows, and any nested
complex type reachable only through the gated member disappears from the definition entirely.

### Setting up case states

The case states are implemented by an enum:

```java
public enum State {
  @CCD(
    label = "Holding",
    hint = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n"
  )
  Holding,

  @CCD(
    label = "Submitted",
    hint = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n"
  )
  Submitted;
}
```

By default the state's `Description` column is the same as its `Name` (i.e. `label`). Set
`@CCD(description = ...)` on the constant to give it a distinct `Description`:

```java
public enum State {
  @CCD(label = "Holding", description = "Case is on hold pending payment")
  Holding;
}
```

By default the CCD state ID is the enum constant name. A constant may override this with
`@JsonProperty`, exactly as case fields do — the annotated value then becomes the state ID
**everywhere** a state is written (the `State` sheet, each event's `PreConditionState(s)` /
`PostConditionState`, and the `AuthorisationCaseState` `CaseStateID` rows):

```java
public enum State {
  @JsonProperty("PREPARE_FOR_HEARING")
  CASE_MANAGEMENT,   // → state ID "PREPARE_FOR_HEARING"
  Open;              // → state ID "Open" (no @JsonProperty, unchanged)
}
```

This matters for three reasons:

- **Consistency.** `FieldUtils.getFieldId` already honours `@JsonProperty` for case fields; states
  were the one place the SDK ignored it.
- **Correctness.** At runtime the case state value is serialised by Jackson, which *does* honour
  `@JsonProperty` — so a service whose state enum carried `@JsonProperty` was previously generating
  definition state IDs that could never match the state values it actually writes. The old
  behaviour was a latent bug, not a contract.
- **Retrofit.** It lets services migrating from hand-written definitions (e.g. FPL, whose enum
  reconciles `CASE_MANAGEMENT → @JsonProperty("PREPARE_FOR_HEARING")`) reuse their existing `State`
  enum instead of maintaining a parallel one.

An enum without `@JsonProperty` on its constants behaves exactly as before, so existing definitions
regenerate byte-for-byte identically. Enums whose `toString()` is overridden (e.g. an `@JsonValue`
`toString()` returning a lowercase id) are also supported: the ID falls back to `toString()`, and
the SDK reads any `@CCD` annotation via `Enum.name()` so such enums no longer throw during
generation.

### Setting up user roles

The `UserRole` class should implement `HasRole` and define all the user roles that are relevant to the case type (both user and case roles).

```java
public enum UserRole implements HasRole {

  CASE_WORKER("caseworker", "CRU"),
  SOLICITOR("caseworker-solicitor", "CRU"),
  CASE_ACCESS_ADMINISTRATOR("caseworker-caa", "CRU"),
  CITIZEN("citizen", "CRU"),
  APPLICANT_1("[APPONE]", "CRU"),
  APPLICANT_2("[APPTWO]", "CRU"),
  CREATOR("[CREATOR]", "CRU"),

  @JsonValue
  private final String role;
  private final String caseTypePermissions;

}
```

The `caseTypePermissions` determine the user's access to the case type and can be overridden in order to implement [shuttering](#Shuttering).

### Adding events

Events can be added by any class that implements `CCDConfig` and they should be defined as spring @Components which will be autowired at runtime.

```java
@org.springframework.stereotype.Component
public class MyConfig implements CCDConfig<CaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    builder.event("submit")
      .forStateTransition(State.Holding, State.Submitted)
      .grant(Permission.CRU, UserRole.APPLICANT_1)
      .aboutToSubmitCallback(this::aboutToSubmit)
      .fields()
        .label("labelSubmitApplicant", "## A heading in XUI")
        .mandatoryWithLabel(CaseData::getName, "Applicant name")
        .optionalWithLabel(CaseData::getEmail, "Applicant email")
        .complex(CaseData::getApplication)
          .mandatory(Application::getSubmittedDate)
          .done()
        .readonly(CaseData::getApplicationType)
    ;
  }

  // Callbacks are defined as method references
  private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
    CaseDetails<CaseData, State> caseDetails,
    CaseDetails<CaseData, State> caseDetailsBefore) {
    //... validate/modify case data before save
  }
}
```

When you need to bind a collection of CCD `ListValue<T>` within an event page, use the `.list(CaseData::getSomeList)` helper. It unwraps the `ListValue` wrapper, so you can continue with `.complex(Item::getSomething)` or `.optional(Item::getFlag)` against the item type.

Callbacks are references to methods. The CCD Config Generator runtime library will handle the routing and execution of event callbacks.

An event can be marked significant on the CaseEvent sheet with `.significant()`:

```java
  builder.event("submit")
    .forStateTransition(State.Holding, State.Submitted)
    .significant()
    ...
```

This sets `SignificantEvent=Y`. It isn't consumed by CCD at runtime; it's a definition-time marker some services use in their own tooling.

An event can allow the caseworker to save a partial submission and resume it later with `.canSaveDraft()`:

```java
  builder.event("create")
    .initialState(State.Open)
    .canSaveDraft()
    ...
```

This sets `CanSaveDraft=Y`. The definition-store importer only allows this on create events (those with no pre-state); setting it on an event with a pre-state fails validation on import.

A field placed on an event can set `ShowSummaryContentOption` — its display order within the event's check-your-answers summary — with `.showSummaryContentOption(n)`, and `NullifyByDefault` — clear the field on submit unless a value is provided — with `.nullifyByDefault()`:

```java
  builder.event("create")
    ...
    .fields()
      .optional(CaseData::getInternalNote)
      .showSummaryContentOption(1)
      .optional(CaseData::getStaleFlag)
      .nullifyByDefault()
    ;
```

The definition-store importer rejects `NullifyByDefault=Y` together with a `DefaultValue` on the same field.

#### Overriding complex-type members on an event

`.complex(getter)` opens a complex field for a specific event and lets you override its members with `.mandatory`/`.optional`/`.readonly`. Each override becomes an `EventToComplexTypes` row keyed by the member's `ListElementCode` (dotted for nested members, e.g. `address.postcode`), letting you re-label, re-hint and conditionally show a member within that event only.

Members carry their per-event label and hint fluently with `.eventLabel(...)` and `.eventHint(...)` — the `EventElementLabel` and `EventHintText` columns — and a show condition via the existing positional argument:

```java
  builder.event("create")
    ...
    .fields()
      .complex(CaseData::getContact)
        .mandatory(Contact::getName)
          .eventLabel("Your full name")
        .optional(Contact::getEmail, "contactName=\"*\"")
          .eventLabel("Your email")
          .eventHint("We only use this to contact you")
        .complex(Contact::getAddress)
          .optional(Address::getPostcode)
            .eventLabel("Postcode")
            .pageId("2")
          .done()
        .done()
    ;
```

`.eventLabel`/`.eventHint` are the fluent equivalents of the trailing label/hint arguments on the positional `.optional`/`.mandatory` overloads, reachable without also threading a show condition or default value — and, unlike those overloads, available on `.readonly` too. `RetainHiddenValue` is carried through from the member's `retainHiddenValue` flag.

`.pageId(...)` sets the member row's `PageID`. The definition-store `EventToComplexTypes` parser does not read `PageID`, so this value does not change how CCD renders the member — it exists purely so a hand-authored definition carrying `PageID` on member rows round-trips through the SDK byte-for-byte. Rarely used columns not read by that parser (`SecurityClassification`, `Publish`, `ShowSummaryChangeOption` — each under ~1.5% of observed member rows) are intentionally left as raw passthrough rather than given SDK setters.

#### Per-field defaults and hidden-value retention

A field placed on an event can set its `CaseEventToFields.DefaultValue` to a raw string with
`.defaultValue(String)`, and can set `RetainHiddenValue=Y` — a value entered while the field is
visible survives it later being hidden by its show condition — with `.retainHiddenValue()`. Both
compose with every other fluent call, including `readonly`/`*NoSummary` field placements and any
`.publish(...)`/`.showSummaryContentOption(...)` calls on the same field:

```java
  builder.event("create")
    ...
    .fields()
      .optional(CaseData::getInternalNote, "otherField=\"*\"")
      .defaultValue("a literal default")
      .retainHiddenValue()
      .readonlyNoSummary(CaseData::getComputedNote)
      .caseEventFieldLabel("Computed note")
      .fieldShowCondition("internalNote=\"*\"")
    ;
```

`caseEventFieldLabel(String)`, `caseEventFieldHint(String)`, `fieldShowCondition(String)` and
`displayContextParameter(String)` are the same fluent, `lastField()`-style calls that set
`CaseEventFieldLabel`/`CaseEventFieldHint`/`FieldShowCondition`/`DisplayContextParameter` on the
field just placed — usable after any field-placement call, including `readonly`/`*NoSummary`
variants that return the `FieldCollectionBuilder` rather than the field itself.

### Configuring the work basket and search fields

There are five methods on the `ConfigBuilder` that allow the configuration of work basket input, work basket results, search input, search results and search cases fields. They all follow the same API:

```java
  builder.searchInputFields()
    .field(CaseData::getApplicationType, "Case type")
    .field(CaseData::getAgreedToReceiveEmails, "Agreed to emails")
    .caseReferenceField();
```

There are some convenience methods for `caseReferenceField`, `stateField`, `createdDateField` and `lastModifiedDate`.

On the work basket and search results fields a sort order can be specified using the `SortOrder` class:

```java
  builder.searchInputFields()
    .field(CaseData::getApplicationType, "Case type", FIRST.DESCENDING)
    .field(CaseData::getAgreedToReceiveEmails, "Agreed to emails", SECOND.ASCENDING)
    .caseReferenceField();
```

For the less common columns — searching *within* a complex field (`ListElementCode`), a `FieldShowCondition`, an explicit `ResultsOrdering`, or scoping a single row to a role — pass a configurer lambda as the third argument:

```java
  builder.searchInputFields()
    // one row per searched leaf of a complex field
    .field(CaseData::getApplicant, "Applicant surname",
        f -> f.listElementCode("surname").showCondition("caseName=\"x\""))
    .field(CaseData::getApplicant, "Applicant surname (admin)",
        f -> f.listElementCode("surname").role(HMCTS_ADMIN));

  builder.searchResultFields()
    .field(CaseData::getApplicant, "Applicant surname",
        f -> f.listElementCode("surname").resultsOrdering(FIRST.DESCENDING));
```

`FieldShowCondition` is valid only on the input sheets and `ResultsOrdering` only on the result sheets (the definition-store importer rejects the wrong one for a given sheet); `ListElementCode` is valid on all four. Calling the lambda once per element code emits one row each, so a complex field can expose several of its leaves.

The `searchCasesFields()` (`SearchCasesResultFields` sheet) builder takes the same lambda overload for its two extra columns — an `AccessProfile`/`UserRole` scope and the `UseCase`. Both default to their historic values (empty `UserRole`, `UseCase = orgcases`) when unset, so plain `.field(...)` calls are unchanged; scope a field to several roles or use cases by calling it once per combination (both columns are part of the row's identity, so the rows stay distinct):

```java
  builder.searchCasesFields()
    .field(CaseData::getCaseName, "Case name")                               // orgcases, no role
    .field(CaseData::getCaseName, "Case name",
        f -> f.role(CASEWORKER).useCase("WORKBASKET"));
```

Similarly, the `searchParty()` builder can declare several parties that share a `SearchPartyName` as long as they point at different collections (`SearchPartyCollectionFieldName`) — each is emitted as its own `SearchParty` row.

### Adding tabs

Tabs follow a similar API to events:

```java
  builder.tab("DraftTab", "Draft case tab")
    .forRoles(UserRole.CASEWORKER, UserRole.SOLICITOR)
    .showCondition("applicationType!=\"A\"")
    .field(CaseData::getApplicationType)
    .field(CaseData::getAgreedToReceiveEmails, ", "applicationType=\"C\"")
    .field(CaseData::getDateSubmitted, null, "#DATETIMEDISPLAY(d  MMMM yyyy)");
```

Adding in multiple user roles will create multiple versions of the tab visible to each user role. If a user has both roles they will see the tab twice.

The tab ID `"CaseHistory"` can be used to configure the special History tab and its order. Use the field
ID `"caseHistory"` for the history field itself:

```java
  builder.tab("CaseHistory", "History")
    .field("caseHistory");
```

If an app does not specify a tab with that ID then a default History tab will be automatically configured as the first tab.

## Permissions

CCD offers a wide array of authorization options. Where possible the CCD config generator will infer those permissions, but they can always be manually defined or overridden.

### Events

Permissions for events are granted on the event builder in two ways:

```java
  builder.event("submit")
    .forStateTransition(State.Holding, State.Submitted)
    .grant(Permission.CRU, UserRole.APPLICANT_1, UserRole.APPLICANT_2)
    .grant(new CaseworkerAccess())
```

The first takes an access level and list of roles. The second makes use of a class that implements `HasAccessControl`:

```java
public class CaseworkerAccess implements HasAccessControl {
  @Override
  public SetMultimap<HasRole, Permission> getGrants() {
    SetMultimap<HasRole, Permission> grants = HashMultimap.create();
    grants.putAll(CITIZEN, Permission.R);
    grants.putAll(SOLICITOR, Permission.R);
    grants.putAll(CASE_WORKER, Permission.CRU);
    grants.putAll(LEGAL_ADVISOR, Permission.CRU);

    return grants;
  }
}
```

### Fields

Field permissions are derived from events. When a field is used as part of an event the permissions on the event will be applied to the field as well.

Permissions can be added manually to individual fields:

```java
  @CCD(
    label = "They have agreed to receive notifications by email",
    access = {CaseworkerAccess.class, Solicitor.class}
  )
  private YesOrNo agreedToReceiveEmails;
```

Multiple access classes on a field compose **additively**: a field's effective grant for a role is
the union of the permissions from every class in the `access` array (see
`AuthorisationCaseFieldGenerator`). This lets a field start from a broad base and widen specific
roles — e.g. `access = {DefaultAccess.class, Applicant2Access.class}` grants everyone the defaults
plus Applicant 2 their own edit rights.

### States

State access is derived from events.

- Roles with CREATE permissions get READ access to the pre and post states
- Roles with READ, UPDATE or DELETE permissions only get access to the post state

Note that using `.forAllStates()` on an event will give READ permissions to all states for the specified roles.

As with fields state access can be manually defined:

```java
public enum State {
  @CCD(
    label = "Holding",
    hint = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n",
    access = {CaseworkerAccess.class, Solicitor.class}
  )
  Holding,

  @CCD(
    label = "Submitted",
    hint = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n",
    access = {CaseworkerAccess.class}
  )
  Submitted;
}
```

### Case type (shuttering)

The recommended way to shutter a service in CCD and XUI is to only grant DELETE access to all roles. This can be done simply by adding:

```java
  configBuilder.shutterService();
```

Individual roles can be shuttered with:

```java
  configBuilder.shutterService(UserRole.SOLICITOR);
```

Roles can be excluded from a shutter with `shutterServiceExclude`, so they keep their normal permissions instead of being set to DELETE. The exclusion applies whether the shutter was requested for the whole service (`shutterService()`) or for individual roles (`shutterService(UserRole.SOLICITOR)`), and takes precedence over both:

```java
  configBuilder.shutterService();
  configBuilder.shutterServiceExclude(UserRole.CASEWORKER_WA_TASK_CONFIGURATION);
```

This is typically used to keep `caseworker-wa-task-configuration` out of a shutter, as dropping that role to DELETE can cause issues for Work Allocation / Task Management.

### Service notice banner

CCD allows one jurisdiction-wide service notice banner, shown by XUI. Configure it with:

```java
  configBuilder.banner(true, "Your system might be running slowly.",
      "https://status.example.com", "Check service status");
```

The `url`/`urlText` arguments are optional — pass `null` or `""` if the banner carries no link. Calling `banner(...)` more than once for the same case type overwrites the previous value, matching the importer's one-banner-per-jurisdiction rule. If `banner(...)` is never called, no `Banner.json` is generated.

### Role to access profile mappings

`caseRoleToAccessProfile` maps a case-type role (a `UserRole` / `HasRole` constant) to one or more
access profiles:

```java
  configBuilder.caseRoleToAccessProfile(UserRole.SOLICITOR)
    .accessProfiles("caseworker-solicitor");
```

Many definitions also map **organisational / IDAM roles that are not case-type roles** (e.g.
`caseworker-ia-system`). Adding these to the `UserRole` enum just to map them would register them and
emit an `AuthorisationCaseType` row. Use `roleToAccessProfile(String)` to map a role by name without
registering it — it emits only the `RoleToAccessProfiles` row and carries the same fluent options:

```java
  configBuilder.roleToAccessProfile("caseworker-ia-system")
    .accessProfiles("caseworker-ia-system", "caseworker-ia-caseofficer");
```

### Case role jurisdiction

Generated `CaseRoles` rows omit the `JurisdictionID` column by default. Call
`emitCaseRoleJurisdiction()` to stamp it (taken from `jurisdiction(...)`) on every case role:

```java
  configBuilder.emitCaseRoleJurisdiction();
```


## Unwrapped types

In some cases you might want to use a Java class for a property but not have it mapped to a complex type. Jackson provides an annotation `@JsonUnwrapped` that will flatten properties in a child class to the parent class. The CCD config generator treats the `@JsonUnwrapped` annotation as a sign that the class should be flattened into fields rather than a complex type.

In our earlier example we had separate fields for `applicant1Name` and `applicant1Email`, but it would be more idiomatic to move those fields into an `Applicant` class.


```java
  @JsonUnwrapped(prefix = "applicant1")
  @CCD(access = {CaseworkerAccess.class})
  private Applicant applicant1;
```

Then defined the properties inside the new model:

```java
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class Applicant {
  @CCD(
    label = "First name",
    access = {SolicitorAccess.class}
  )
  private String name;

  @CCD(
    label = "Email address",
    typeOverride = Email
  )
  private String email;
}
```

The `applicant1` property can be giving a prefix that is appended to every field name. In this case the fields would be `applicant1Name` and `applicant1Email`, just as they were before. When a prefix is used the `@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)` is mandatory.

### Permissions

When an unwrapped property is paired with an explicit permission (`@CCD(access = {CaseworkerAccess.class})`) that permission will be applied to the unwrapped properties.

Any permissions defined on an unwrapped field will be added to the parent. In the example above `applicant1Name` will have both `CaseworkerAccess.class` (from the `applicant1` property) and `SolicitorAccess.class` (from the `name` property).

In order to replace the access defined by the parent class use the `inheritAccessFromParent` option:

```java
  @CCD(
    label = "First name",
    access = {SolicitorAccess.class},
    inheritAccessFromParent = false
  )
  private String name;
```

Now `applicant1Name` will only have `SolicitorAccess.class`.

### Lombok

In almost all cases there are no issues combining the CCD config generator with Lombok generated code. However, it is necessary to add an explicit constructor with `@JsonCreator` to classes inside an unwrapped class.

For example, adding a `Document` to the `Applicant` class would require it to have an explicit constructor:

```java
public class Document {

  @JsonProperty("document_url")
  private String url;

  @JsonProperty("document_filename")
  private String filename;

  @JsonProperty("document_binary_url")
  private String binaryUrl;

  @JsonCreator
  public Document(
      @JsonProperty("document_url") String url,
      @JsonProperty("document_filename") String filename,
      @JsonProperty("document_binary_url") String binaryUrl
  ) {
    this.url = url;
    this.filename = filename;
    this.binaryUrl = binaryUrl;
  }
}
```

### Jackson configuration

Jackson does require some configuration in order to handle dates and the `HasRole` enum:

```java
@Configuration
public class JacksonConfiguration {

  @Primary
  @Bean
  public ObjectMapper getMapper() {
    ObjectMapper mapper = JsonMapper.builder()
      .configure(ACCEPT_CASE_INSENSITIVE_ENUMS, true)
      .enable(INFER_BUILDER_TYPE_BINDINGS)
      .serializationInclusion(JsonInclude.Include.NON_NULL)
      .build();

    SimpleModule deserialization = new SimpleModule();
    deserialization.addDeserializer(HasRole.class, new HasRoleDeserializer());
    mapper.registerModule(deserialization);

    JavaTimeModule datetime = new JavaTimeModule();
    datetime.addSerializer(LocalDateSerializer.INSTANCE);
    mapper.registerModule(datetime);
    mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES));

    return mapper;
  }
}
```

Where `HasRoleDeserializer` is:

```java
public class HasRoleDeserializer extends StdDeserializer<HasRole> {
  static final long serialVersionUID = 1L;

  public HasRoleDeserializer() {
    this(null);
  }

  protected HasRoleDeserializer(Class<?> vc) {
    super(vc);
  }

  @Override
  public HasRole deserialize(JsonParser parser, DeserializationContext context) throws IOException {
    JsonNode node = parser.readValueAsTree();

    return Arrays
      .stream(UserRole.values())
      .filter(r -> r.getRole().equals(node.asText()))
      .findFirst()
      .get();
  }
}
```

## Customising the generated JSON

The output of the `generateCCDConfig` task is a folder containing JSON configuration, further customisation of which can be achieved using standard Gradle functionality.

For example, the below task will combine the output of the config generator with JSON definitions stored in a 'static' directory.

```groovy
task generateCCDDefinition(type: Copy) {
  from tasks.generateCCDConfig.outputs
  from file('static')
  into layout.buildDirectory.dir('json-definitions')
}
```

This static directory can contain JSON configuration for CCD features not covered by the config generator (eg. Challenge Questions).

## Reference projects

To see a full working implementation of a CCD service using the CCD config generator check one of these projects:

- https://github.com/hmcts/nfdiv-case-api
- https://github.com/hmcts/adoption-cos-api

## Migrating a JSON definition to Java (ccd-definition-converter)

Teams that already maintain a hand-written JSON CCD definition (the per-sheet format consumed
by `ccd-definition-processor`'s `json2xlsx`) can bootstrap the equivalent config-generator Java
with the `ccd-definition-converter` SDK subproject. It runs in **two modes**:

- **Generate mode** (the default, below) reads the JSON sheets and emits a brand-new
  `@CCD`-annotated `CaseData`, `State`/`UserRole`/`FixedList` enums, `@ComplexType` classes,
  access classes and `CCDConfig` classes. This is the path for **map-based** services whose model
  is a `HashMap` subclass with no fields to annotate (e.g. IA's `AsylumCase`).
- **Retrofit mode** (`--retrofit`, further below) does *not* generate a fresh model. Instead it
  resolves each definition field against the team's **existing** Java model using the SDK's own
  Jackson-faithful rules. `--report-only` reports how well the two line up (phase 1); without it,
  the converter additionally emits a `git apply`-able **annotation patch** against the model plus the
  still-generated companion config/enum/access sources (phase 2). This is the path for the
  **team-owned-POJO** services (FPL, Civil, ET, SSCS) that already maintain a rich domain model.

See [`docs/retrofit-existing-models-proposal.md`](docs/retrofit-existing-models-proposal.md) for
which archetype fits which mode.

### Generate mode

```bash
./gradlew -p sdk :ccd-definition-converter:run --args='
  --input        /path/to/definitions/appeal/json
  --case-type    Asylum
  --output-src   /path/to/service/src/main/java
  --model-package  uk.gov.hmcts.ia.model
  --overlay-suffix prod=CCD_DEF_ENV:prod --overlay-suffix nonprod=!CCD_DEF_ENV:prod
  --report-dir   build/ccd-conversion'
```

#### Generated package layout

Generated code goes into the service's **main source tree** and is laid out to mirror the mature
hand-written services (nfdiv/sptribs), so a team owns and edits it directly. The **root config
package** is derived from `--model-package` by cutting it at its first `model`, `models` or `domain`
segment, then appending `.ccd` unless the cut result already ends with `.ccd`:

| `--model-package` | derived root package |
|---|---|
| `uk.gov.hmcts.probate.model.ccd.raw` | `uk.gov.hmcts.probate.ccd` |
| `uk.gov.hmcts.reform.fpl.model` | `uk.gov.hmcts.reform.fpl.ccd` |
| `uk.gov.hmcts.reform.sscs.ccd.domain` | `uk.gov.hmcts.reform.sscs.ccd` |
| `uk.gov.hmcts.reform.prl.models.dto.ccd` | `uk.gov.hmcts.reform.prl.ccd` |
| `uk.gov.hmcts.foo.bar` (no marker segment) | `uk.gov.hmcts.foo.bar.ccd` |

Override it with `--root-package <pkg>` (or the equivalent legacy `--config-package <pkg>`) when a
team wants a specific home. Under `<root>` the converter emits:

```
<root>/                       config beans, one per concern (finding #6):
  ├─ <Prefix>CaseType.java      case type + jurisdiction identity, flags, banner
  ├─ <Prefix>Grants.java        state + complex-type grants
  ├─ <Prefix>Tabs.java          tabs
  ├─ <Prefix>WorkBasket.java    work-basket input/result fields
  ├─ <Prefix>Search.java        search input/result, search-cases, criteria, parties
  ├─ <Prefix>NoticeOfChange.java, <Prefix>RoleToAccessProfiles.java, <Prefix>Categories.java
  ├─ event/                     one @Component CCDConfig per event (finding #1):
  │    ├─ CreateCase.java          PascalCase of the event ID; ID as a `static final String` constant
  │    └─ event/page/             one class per wizard page (finding #2):
  │         └─ CreateCasePage1.java   `public static void apply(fields)` called by the event class
  └─ access/                    HasAccessControl classes referenced by @CCD(access = {…})
```

Model classes (`CaseData`, complex types, `State`, `UserRole`, enums, `EnvironmentFlags`) stay in
`--model-package`. Each config bean is emitted only when its concern carries content (`CaseType`
always). A single wizard page large enough to overflow the JVM method-size limit on its own splits
into documented `<Page>FieldsN` fragments within its page package — the only surviving numbered form.

Key behaviours:

- **Callbacks** — the converter emits **no** SDK callback wiring at all. Every callback column
  (`CallBackURL*` and its `RetriesTimeout*` on the `CaseEvent` sheet, `CallBackURLMidEvent` and its
  retry on `CaseEventToFields`) is carried through **verbatim** via passthrough, `${CCD_DEF_*}`
  placeholders and all — so the regenerated definition points at the migrated service's original
  endpoints unchanged and callbacks are provably unaltered. Adopting the SDK's in-process callbacks
  is a later, opt-in, per-event step: delete that event's passthrough graft and register a handler
  (`.aboutToSubmitCallback(...)`, `page(id, ::midEvent)`, …). (There is no `--callback-mode` option
  or `callback-map.json` any more.)
- **Per-environment overlays** — files such as `CaseEvent-prod.json` / `CaseEvent-nonprod.json`
  become environment-guarded Java (an `EnvironmentFlags.flag(...)` check), configured via
  repeatable `--overlay-suffix suffix=[!]ENV_VAR:value` (defaults ship for `prod`/`nonprod`).
- **Gaps and passthrough** — anything the SDK cannot express in code (unsupported sheets like
  `Banner`/`UserProfile`, unsupported columns, non-derivable authorisations) is written to a
  gap report (`gap-report.json`/`.md`) and, where safe, passed through as raw JSON to be merged
  into the generated definition after `generateCCDConfig` runs. Nothing is silently dropped.

Correctness is proven by the `roundTripTest` task: it converts a definition to Java, compiles
it, runs the generator, and semantically diffs the regenerated JSON against the input, tolerating
only an explicit, documented set of superficial config-generator differences.

### Access-class emission policy (both modes)

The converter reproduces a definition's `AuthorisationCaseField` grants as `@CCD(access = {…})` on
each field, after subtracting the grants the SDK injects automatically (event grants, `caseHistory`,
tab/search read). Whatever a field asks for beyond that baseline is its **residual** grant map. The
converter expresses every residual as a **union of named access-group classes**, exactly as a
hand-written HMCTS model does (e.g. `@CCD(access = {DefaultAccess.class, Applicant2Access.class})`).
This exploits the SDK's proven additive composition — a field's effective grant for a role is the
union of the permissions from every class it names (see
`AuthorisationCaseFieldGenerator.addPermissionsFromFields`). Class names never appear in the emitted
`AuthorisationCaseField`, only the resolved grants, so any decomposition whose classes' grants union
back to the residual round-trips byte-for-byte. The decomposition is deterministic (identical in
generate and retrofit mode — `AccessClassComputer`):

1. **Atoms.** The finest grain is one class per distinct `(role → CRUD)` pair that occurs — small and
   semantic (`CaseworkerCruAccess`, `CitizenRAccess`). A residual with distinct roles decomposes into
   one atom per role.
2. **Groups.** Frequently co-occurring atom-sets are mined into named bundles by a greedy
   frequent-itemset pass — the atom-set covering the most residual across the case type is carved out
   first, then the pass repeats on what remains. A group must be used by **≥ 3 fields** and bundle
   **≥ 2 atoms** to earn a name. The single most-used group is named **`DefaultAccess`** for
   recognisability (the bundle most fields carry); the rest are named from their content — each role's
   short token plus its CRUD in title case, sorted by role (e.g. `{caseworker=CRU, citizen=R}` →
   `CaseworkerCruCitizenRAccess`), collision-suffixed and stable across re-runs. A name over 70
   characters is truncated to the first role's token, a role count and a short stable digest.
3. **Per-field cover.** Each residual is covered greedily by the groups that carved it plus any
   leftover atoms. When a field would need more than **6 classes**
   (`AccessClassComputer.MAX_CLASSES_PER_FIELD`), the whole residual falls back to **one dedicated,
   semantically-named class** (never `AccessNN`) shared by every field with that exact residual — so
   per-field arrays stay readable.

Only classes actually referenced by some field's cover are emitted. The outcome is recorded per case
type in `CaseTypeModel.accessSummaryNote` (and the gap report's *Access emission* section): the total
class count broken into groups/atoms/dedicated fallbacks, the per-field array distribution (avg/max),
and the mined-group table.

### Retrofit mode (`--retrofit --report-only`)

Phase 1 of retrofit is a **report-only matcher**. Given a definition and the team's existing
model source, it resolves every data-bearing `CaseField` ID against that model using the exact
resolution the SDK applies at generation time (`FieldUtils.getFieldId`,
`CaseFieldGenerator.appendUnwrapped`, the `ReflectionUtils.doWithFields` superclass walk,
`FieldUtils.isFieldIgnored`), classifies each field, and writes a match report. **No source is
mutated and no annotation patch is emitted — that is phase 2.**

```bash
./gradlew -p sdk :ccd-definition-converter:run --args='
  --retrofit --report-only
  --input             /path/to/civil-ccd-definition/ccd-definition/civil
  --case-type         CIVIL
  --model-source-root /path/to/civil-service/src/main/java
  --model-package     uk.gov.hmcts.reform.civil.model
  --model-class       CaseData
  --report-dir        build/retrofit'
```

The resolver mirrors the SDK rule-for-rule: field name → ID, `@JsonProperty("id")` override,
recursive `@JsonUnwrapped` prefix composition (`prefix + capitalize(child)`, and prefix-less
unwraps verbatim), superclass fields, and `@JsonIgnore` / `@CCD(ignore = true)` exclusion. It maps
each resolved field's Java type through the SDK's own inference (`String`→`Text`, `LocalDate`→`Date`,
enum→`FixedRadioList`, `Collection` with generic-wrapper descent) and detects concrete
`value`-bearing collection wrappers the SDK's `hasGenerics()` descent mis-resolves (which need a
per-field `@CCD(typeParameterOverride = …)`, decision 8). Every data-bearing field lands in exactly
one bucket:

| Bucket | Meaning | Action |
|---|---|---|
| `EXACT_MATCH` | Resolves; Java type infers to the definition's FieldType | annotate only (`@CCD` for label/hint/access) |
| `TYPE_CONFLICT` | Resolves; type infers differently | `@CCD(typeOverride=…)` — or `typeParameterOverride` for a concrete wrapper |
| `UNMATCHED_DEFINITION_FIELD` | No Java property resolves | synthesise a typed `@CCD` field on the model class (decision 4) |

plus the reverse `UNMATCHED_JAVA_FIELD` list (model fields with no definition ID → `@CCD(ignore=true)`),
a state-enum verdict (state ID = constant `@JsonProperty` else `toString()`, decision 3), a
collection-wrapper survey (generic vs concrete counts), the `@JsonUnwrapped`/`@JsonIgnore`/superclass
counts, and a map-based-model check that reports *"retrofit not applicable — use generate mode"* for a
`HashMap` subclass. Output is `retrofit-report.md` (human-readable) + `retrofit-report.json`
(structured) under `--report-dir`. Sample:

```markdown
# CCD Retrofit Match Report
- **Case type:** Benefit
- **Model class:** `uk.gov.hmcts.reform.sscs.ccd.domain.SscsCaseData`

## Summary
| Bucket | Count | % of data-bearing |
|---|---|---|
| EXACT_MATCH | 147 | 26.9% |
| TYPE_CONFLICT | 365 | 66.7% |
| UNMATCHED_DEFINITION_FIELD | 35 | 6.4% |

**Resolved (exact + type-conflict): 93.6%. Exact: 26.9%.**
```

Measured resolved rates (name resolution) against the four POJO archetypes: **FPL ~71 %, ET ~98 %,
Civil ~95 %, SSCS ~94 %** — see the proposal for the per-fixture breakdown and how the FPL figure
lifts far above the proposal's 28 % top-level-name-only hand floor once `@JsonUnwrapped` and
superclasses are walked.

### Retrofit mode phase 2 (`--retrofit`, patch emission)

Dropping `--report-only` graduates retrofit to **phase 2**: the same matcher runs, and then the
converter additionally emits (1) an annotation **patch** against the team's model and (2) the
still-generated **companion sources** (config/enum/access), targeted at that model.

```bash
./gradlew -p sdk :ccd-definition-converter:run --args='
  --retrofit
  --input             /path/to/civil-ccd-definition/ccd-definition/civil
  --case-type         CIVIL
  --model-source-root /path/to/civil-service/src/main/java
  --model-package     uk.gov.hmcts.reform.civil.model
  --model-class       CaseData
  --output-src        /path/to/civil-service/src/main/java
  --report-dir        build/retrofit'
```

`--output-src` is **required** in phase 2 (it receives the companion sources); `--report-only`
short-circuits back to phase 1 and needs neither. The root package for the companions follows the
same rule as generate mode — derived from `--model-package` (here `uk.gov.hmcts.reform.civil.model`
→ `uk.gov.hmcts.reform.civil.ccd`) unless `--root-package`/`--config-package` overrides it — so the
companions land in the service's main source tree beside the model, laid out as documented under
[Generated package layout](#generated-package-layout) (events in `<root>.event`, page classes in
`<root>.event.page`, access classes in `<root>.access`, config beans split by concern in `<root>`).
The retrofit clone-regeneration script writes exactly this tree (untracked in the clone's git
status), retiring the old flat `generated-config/` directory. Invalid combinations are rejected with
a clear error.

**What the patch (`build/retrofit/retrofit.patch`) contains** — a unified diff, `git apply`-able from
the model repo root, produced with JavaParser's `LexicalPreservingPrinter` (minimal churn — untouched
lines are byte-preserved) and java-diff-utils:

- **matched fields** gain `@CCD(...)` carrying the definition metadata the SDK reads — label, hint,
  showCondition, regex, categoryID, `searchable=false`, retainHiddenValue, min/max and the
  `access = {…}` classes — sourced from the same `FieldModel` the linker computes for generate mode,
  and emitted only when `@CCD` carries something (mirroring `FieldEmitHelper`'s `hasAny` rule);
- **type-conflict fields** additionally gain `typeOverride`/`typeParameterOverride`; a concrete
  value-wrapper collection (`List<DocItem>`) gets `@CCD(typeOverride = FieldType.Collection,
  typeParameterOverride = "<element>")` (decision 8);
- **unmatched Java fields** (not already `@JsonIgnore`/`@CCD(ignore=true)`) gain
  `@CCD(ignore = true)` — required for fidelity, so the SDK does not reflect them into CaseField rows
  the definition has no counterpart for;
- **unmatched definition fields** (decision 4) are **synthesised** as new typed private fields on
  `--model-class`, each with `@CCD(...)` (and a `@JsonProperty` when the ID is not a legal bean name),
  grouped in one clearly-delimited block at the end of the class body. Three placement guards apply:
  - a synthesised field whose Java name **collides with an existing member** (a `@JsonUnwrapped`
    parent, or a `@JsonProperty`-renamed field the definition also lists) is **skipped** and reported
    as a gap rather than re-declared (which would not compile);
  - when synthesising every field would push the class's Lombok all-args constructor past the JVM's
    ~254-slot limit, the fields are moved into a new **`CaseDataExtra`** class (added by the patch)
    and the root class gains one prefix-less `@JsonUnwrapped private CaseDataExtra caseDataExtra;`
    member — prefix-less unwrapping flattens the member IDs verbatim, so the CCD field IDs are
    unchanged (threshold overridable with `--type-package-hint`'s sibling option in code; the borderline
    "even one member tips it" case is flagged);
  - a class using a hand-written single-arg `@JsonCreator` + `@Builder` idiom is **not** synthesised
    into (appending a field breaks the builder's constructor binding); its members route to the gap
    report for manual placement;
- **complex-type members** get the same annotate/ignore/synthesise treatment (including the
  `typeParameterOverride` reconciliation for nested collection members) on each model class the
  definition's `ComplexTypes` rows resolve to;
- **imports** the synthesised fields need are added once per file; a simple name already bound to a
  *different* type in that compilation unit is written **fully-qualified** instead of adding a
  clashing import;
- the patch is **idempotent**: phase 2 targets unannotated models, so a field already carrying
  `@CCD` is skipped (a re-run produces no-op hunks for it).

**Disambiguating type names** (`--type-package-hint`). When the definition references a simple type
name declared in more than one model sub-package with nothing in the `ComplexTypes` JSON to choose
between them (Civil's `HearingLength`, `CaseLocationCivil`), the resolver refuses to guess and the
reference fails to resolve. Supply `--type-package-hint TypeName=fully.qualified.package` (repeatable)
to pin it; an unknown hint (no such type in that package) errors clearly. For example:

```bash
  --type-package-hint HearingLength=uk.gov.hmcts.reform.civil.enums.dq
  --type-package-hint CaseLocationCivil=uk.gov.hmcts.reform.civil.model.defaultjudgment
```

**Companion sources** (under `--output-src`, in `--config-package`) reuse generate mode's emitters,
retargeted at the team's model: the `CCDConfig` classes' typed getters reference the team's
`CaseData` and resolved member names (fields reached through `@JsonUnwrapped` are placed via the
clustered `.complex(CaseData::getParent).x(Parent::getMember)` form); access classes as generate
mode; the team's `State` enum is reused when every state ID resolves (proposal decision 3) else a
fresh one is generated; `UserRole` is generated fresh; a `FixedList` is generated only where the
model has no matching enum type; and passthrough + gap report are as generate mode.

**Apply-then-verify workflow.** Review the patch, apply it, then prove it:

```bash
cd /path/to/civil-service
git apply /path/to/build/retrofit/retrofit.patch      # annotate the model in place
# the companion config/enum/access sources are already under src/main/java
./gradlew generateCCDConfig                            # regenerate the definition from the annotated model
```

Then diff the regenerated definition against the original JSON (the round-trip host is the service's
own build — proposal decision 5/9). This end-to-end apply-then-regenerate loop is exercised in the
converter's own `roundTripTest` by `RetrofitRoundTripTest`, which applies the emitted patch in-memory,
compiles the patched model plus the companion sources, runs the generator, and asserts
`NormalisingCcdConfigComparator` finds zero unexplained diffs.

### Retrofit mode phase 3 (`bin/retrofit-verify`, end-to-end pipeline)

Phase 3 automates the apply-then-verify loop above against a **real service checkout**, running the
round-trip inside the service's *own* Gradle build (proposal decisions 5 & 9). It never mutates the
model or definition checkouts — it copies the model repo to a work dir and patches only the copy, so
git submodules used as fixtures stay pristine.

```bash
sdk/ccd-definition-converter/bin/retrofit-verify \
  --model-repo   /path/to/civil-service \
  --definition   /path/to/civil-ccd-definition/ccd-definition/civil \
  --case-type    CIVIL \
  --model-class  uk.gov.hmcts.reform.civil.model.CaseData \
  --env          CCD_DEF_ENV=nonprod
```

The script prints each stage and a final residual-diff summary. The five stages:

1. **convert** — runs `--retrofit` (phase 2) to emit `retrofit.patch` + companion sources;
2. **copy** — `rsync`/`cp` the model repo to `<build>/retrofit-verify/<case-type>/model-copy`
   (excluding `.git`/`build`/`.gradle`) — the original is read-only;
3. **apply** — `git apply` the patch to the copy (no `--recount`: the emitted hunk counts are exact,
   and `--recount` mis-parses the many concatenated single-file diffs);
4. **publish + generate** — `./gradlew -p sdk publishToMavenLocal`, then run `generateCCDConfig` in
   the copy wired via a Gradle **init script** (adds `mavenLocal`, applies `hmcts.ccd.sdk`, adds the
   companion sources as an extra `srcDir`, points `ccd { rootPackage }` at the companion config
   package). The init script keeps the service's own `build.gradle` byte-for-byte untouched; quality
   gates (`test`/`checkstyle*`/`spotless*`/`spotbugs*`) that the project actually declares are excluded
   so only the compile + generate runs;
5. **verify** — `RetrofitVerifyCli` aggregates the definition repo (reusing the converter's reader +
   `ExpectedDefinitionBuilder`'s overlay/env handling) and diffs it against the generated output with
   `NormalisingCcdConfigComparator`, printing residual diffs bucketed by sheet.

Useful flags: `--model-source-root` (default `<model-repo>/src/main/java`), `--config-package`
(default `<modelPackage>.ccd.generated`), `--work-dir`, repeatable `--env KEY=VALUE` and
`--overlay-suffix suffix=[!]ENV:val`, `--gradle-arg` (extra args for the service's `gCC`),
`--skip-publish` (reuse an already-published mavenLocal SDK), and `--stop-after
convert|copy|patch|publish|generate` for debugging. Exit code: 0 = zero residual diffs, 1 = residual
diffs, >1 = a pipeline stage failed. The comparator entry point is also runnable directly as
`./gradlew -p sdk :ccd-definition-converter:retrofitVerify --args='…'`.

See [`docs/retrofit-existing-models-proposal.md`](docs/retrofit-existing-models-proposal.md) §Phase 3
for the Civil pilot outcome — how far each stage got, the converter bugs the pilot surfaced and fixed,
and the residual blocker.

### Fidelity

See [`docs/json-conversion-fidelity.md`](docs/json-conversion-fidelity.md) for the full picture.
In short: every construct the SDK can express round-trips byte-identically, modulo an enumerated
set of cosmetic normalisation rules (column aliases, defaults, ordering, and similar
spelling-only differences). State authorisation is reproduced exactly (via
`ConfigBuilder.explicitStateGrants()`), field authorisation is reproduced exactly (the converter
emits `Event.explicitGrants()` on every event so event grants no longer cascade onto their fields,
and derives each field's `@CCD(access = …)` classes to match the input's `AuthorisationCaseField`
rows), role-scoped search/workbasket fields round-trip through the SDK's role-scoped
`SearchBuilder.field(id, label, role)` overload, and the `AuthorisationComplexType` sheet round-trips
through verbatim passthrough. The array shorthands (`UserRoles[]` and `AccessControl[]`) that
definitions use to grant many roles in one row — which `ccd-definition-processor` flattens at build
time — are now expanded on both sides across every `Authorisation*` sheet, so grants encoded that
way round-trip exactly. Two categories of genuine semantic difference are accepted as permanent,
each absorbed by a narrowly-scoped comparator rule so the round-trip tests pass while the difference
stays visible in the gap report: the SDK's event-level `publishToCamunda()` switch publishes every
non-complex field of a publishing event rather than only the fields the input marked `Publish=Y`
(`PUBLISH_CASCADE`); and the SDK grants `CR` on immutable Label/READONLY display fields to any role
that holds a grant on the event where the field is read-only, regardless of the input's narrower
per-field grants (`IMMUTABLE_FIELD_CR`). Callbacks are no longer rewritten at all: the converter emits
no SDK callback wiring and carries every callback column through verbatim via passthrough, so the
`CALLBACK_URL` normalisation rule is retired and callback URLs (including per-page mid-event URLs, with
their `${CCD_DEF_*}` placeholders) now round-trip byte-for-byte and are compared exactly. Seven
real-service fixture tests (ia/sscs/fpl/ET/civil/prl/probate) convert, compile and round-trip
end-to-end, with residuals of 5/66/24/34/211/199/442 diff lines. The fixtures stay `@Disabled` for the
genuinely structural tails — env-gated overlay-only CaseData fields, `PublishAs` (separate PR), a
vestigial `AuthorisationCaseState LiveTo`, and `src/main` SDK-generator limitations — each itemised in
the tests' `@Disabled` reasons and in the fidelity doc.

## Where to get help

If you are interested in using the CCD config generator or have a question the best place to ask is on [DTS slack](https://hmcts-dts.slack.com/archives/C01MDKSFEL8)

## Contributing

Pull requests are very welcome. Please ensure the [tests](https://github.com/hmcts/dtsse-ccd-config-generator/blob/master/ccd-config-generator/src/test/java/uk/gov/hmcts/ccd/sdk/E2EConfigGenerationTests.java) have been updated to cover any new or altered functionality. The tests are based on comparing the generated JSON output to [expected](https://github.com/hmcts/dtsse-ccd-config-generator/tree/master/ccd-config-generator/src/test/resources/CARE_SUPERVISION_EPO) - expected output should be added to/modified as necessary.

### Local development

In order to link a local version of CCD config generator to a project you can use the `publishToMavenLocal` gradle task then add:

```groovy
  implementation group: 'com.github.hmcts', name: 'ccd-config-generator', version: 'DEV-SNAPSHOT'
```

To the project dependencies.
