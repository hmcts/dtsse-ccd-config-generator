# CCD Config Generator

## Installation

Add the plugin to your `build.gradle`

```groovy
plugins {
  id 'hmcts.ccd.sdk' version '0.3.2'
}
```

Add the configuration bindings library to your compile dependencies

```groovy
dependencies {
  compile "uk.gov.hmcts:ccd-sdk-types:0.3.2"
}
```

And set the destination for the generated config

```
ccd {
  configDir = file('ccd-definition')
}
```

### Repositories

The tool is not yet published to jcenter/the gradle plugin repository.
 
To try it out your will need to add the following to your `build.gradle`:

```groovy
repositories {
  maven {
    url  "https://raw.githubusercontent.com/banderous/ccd/master"
  }
}
```

And the following in `settings.gradle`

```groovy
pluginManagement {
  repositories {
    maven {
      url 'https://raw.githubusercontent.com/banderous/ccd/master'
    }
    gradlePluginPortal()
  }
}
```

## Usage

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

