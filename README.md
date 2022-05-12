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
* [Permissions](#permissions)
  + [Events](#events)
  + [Fields](#fields)
  + [States](#states)
  + [Case type (shuttering)](#case-type--shuttering-)
* [Unwrapped types](#unwrapped-types)
  + [Permissions](#permissions-1)
  + [Lombok](#lombok)
  + [Jackson configuration](#jackson-configuration)
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

## Installation

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

### Config generation

The `generateCCDConfig` task generates the configuration in JSON format to the configured folder:

```shell
./gradlew generateCCDConfig
or
./gradlew gCC
```

Once the JSON definition has been created an XSLX file can be generated using the [ccd-definition-processor](https://github.com/hmcts/ccd-definition-processor/):

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
public class MyConfig implements CCDConfig<CaseData, State, UserRole> {

  @Override
  public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
    builder.caseType("MY_CASE_TYPE", "My Case Type", "Case type description");
    builder.jurisdiction("MY_JURISDICTION", "Jurisdiction", "Jurisdiction description");
    builder.setCallbackHost(System.getenv().getOrDefault("API_URL", "http://localhost:4013"));
  }

}
```

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

### Setting up case states

The case states are implemented by an enum:

```java
public enum State {
  @CCD(
    name = "Holding",
    label = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n"
  )
  Holding,

  @CCD(
    name = "Submitted",
    label = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n"
  )
  Submitted;
}
```

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

Can be added by any class that implements `CCDConfig` and they should be defined as spring @Components which will be autowired at runtime.

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

Through the fields API it is possible to define optional and mandatory fields, fields from complex types, custom labels for events, show conditions and default values.

Callbacks are references to methods. The CCD Config Generator runtime library will handle the routing and execution of event callbacks.

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
    name = "Holding",
    label = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n",
    access = {CaseworkerAccess.class, Solicitor.class}
  )
  Holding,

  @CCD(
    name = "Submitted",
    label = "### Case number: ${[CASE_REFERENCE]}\n ### ${applicant1Name}\n",
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

I'm almost all cases there are no issues combining the CCD config generator with Lombok generated code. However, it is necessary to add an explicit constructor with `@JsonCreator` to classes inside an unwrapped class.

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

## Reference projects

To see a full working implementation of a CCD service using the CCD config generator check one of these projects:

- https://github.com/hmcts/nfdiv-case-api
- https://github.com/hmcts/adoption-cos-api

## Where to get help

If you are interested in using the CCD config generator or have a question the best place to ask is on [DTS slack](https://hmcts-dts.slack.com/archives/C01MDKSFEL8)

## Contributing

Pull requests are very welcome. Please ensure the tests have been updated to demonstrate the change.

### Local development

In order to link a local version of CCD config generator to a project you can use the `publisTohMavenLocal` gradle task then add:

```groovy
  implementation group: 'com.github.hmcts', name: 'ccd-config-generator', version: 'DEV-SNAPSHOT'
```

To the project dependencies.



