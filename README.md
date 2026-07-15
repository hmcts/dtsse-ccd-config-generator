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
