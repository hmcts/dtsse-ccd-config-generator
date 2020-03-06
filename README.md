# CCD Config Generator ![Java CI](https://github.com/hmcts/ccd-config-generator/workflows/Java%20CI/badge.svg?branch=master) ![GitHub tag (latest SemVer)](https://img.shields.io/github/v/tag/hmcts/ccd-config-generator?label=release)

Autogenerate your CCD configuration from your Java domain model using a Gradle build task.

The generator inspects your Java model and generates:

* AuthorisationCaseEvent
* CaseEvent
* CaseEventToComplexTypes
* CaseEventToFields
* ComplexTypes
* FixedLists
* CaseField

## Installation

Add the plugin to your `build.gradle` file in the project containing your Java domain model:

```groovy
plugins {
  id 'hmcts.ccd.sdk' version '[latest version at top of page]'
}
```

And set the destination for the generated config

```
ccd {
  configDir = file('ccd-definition')
}
```

## Usage

The `generateCCDConfig` task generates the configuration:

```shell
./gradlew generateCCDConfig
```

The generator is configured by providing an implementation of the CCDConfig<> interface:

```java
public class ProdConfig implements CCDConfig<CaseData, State, UserRole> {

    @Override
    public void configure(ConfigBuilder<CaseData, State, UserRole> builder) {
        // Permissions can be set on states as well as events.
        builder.grant(State.Gatekeeping, "CRU", UserRole.GATEKEEPER);
        
        // Webhooks can be set with a custom generated convention.
        builder.setWebhookConvention(this::webhookConvention);
        
        // Events can belong to any number of states.
        builder.event("addNotes")
            .forStates(State.Submitted, State.Gatekeeping)
            .grant("CRU", UserRole.HMCTS_ADMIN)
            .aboutToStartWebhook()
            .aboutToSubmitWebhook()
            .fields()
                .optional(CaseData::getAllApplicants)
                .complex(CaseData::getJudgeAndLegalAdvisor)
                    .readonly(JudgeAndLegalAdvisor::getJudgeFullName)
                    .readonly(JudgeAndLegalAdvisor::getLegalAdvisorName);

    }

    private String webhookConvention(Webhook webhook, String eventId) {
        return "/" + eventId + "/" + webhook;
    }
}
```

