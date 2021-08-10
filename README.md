# CCD Config Generator ![Java CI](https://github.com/hmcts/ccd-config-generator/workflows/Java%20CI/badge.svg?branch=master) ![GitHub tag (latest SemVer)](https://img.shields.io/github/v/tag/hmcts/ccd-config-generator?label=release)

Write CCD configuration in Java.

##### Why

* Compile-time type checking & auto-refactoring for CCD configuration
* Auto-generation of CCD schema based on your existing Java domain model (CaseField, ComplexType, FixedList etc)
* Avoid common CCD configuration mistakes with a simplified API
* Your application's code as the single source of truth

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

## Configuration

The generator is configured by providing one or more implementations of the [CCDConfig](https://github.com/hmcts/ccd-config-generator/blob/master/ccd-config-generator/src/main/java/uk/gov/hmcts/ccd/sdk/api/CCDConfig.java) interface:

Implementations should be defined as spring @Components for autowiring at runtime.

```java
@org.springframework.stereotype.Component
public class MyConfig implements CCDConfig<CaseData, State, UserRole> {

    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        // Permissions can be set on states as well as events.
        builder.grant(State.Gatekeeping, Permission.CRU, UserRole.GATEKEEPER);

        builder.event("addNotes")
            .forStates(State.Submitted, State.Gatekeeping)
            .grant(Permission.CRU, UserRole.HMCTS_ADMIN)
            .aboutToSubmitCallback(this::aboutToSubmit)
            .fields()
            .optional(CaseData::getAllApplicants)
            .complex(CaseData::getJudgeAndLegalAdvisor)
                .readonly(JudgeAndLegalAdvisor::getJudgeFullName)
                .readonly(JudgeAndLegalAdvisor::getLegalAdvisorName);

    }

    // Callbacks are defined as method references
    private AboutToStartOrSubmitResponse<CaseData, State> aboutToSubmit(
      CaseDetails<CaseData, State> caseDetails,
      CaseDetails<CaseData, State> caseDetailsBefore) {
      //... validate/modify case data before save
  }
}
```

