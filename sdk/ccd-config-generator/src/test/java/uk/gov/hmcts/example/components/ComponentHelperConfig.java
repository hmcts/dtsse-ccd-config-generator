package uk.gov.hmcts.example.components;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
public class ComponentHelperConfig implements CCDConfig<ComponentHelperCaseData, ComponentHelperConfig.State,
    ComponentHelperConfig.Role> {

  enum State {
    Open
  }

  enum Role implements HasRole {
    CASEWORKER;

    @Override
    public String getRole() {
      return "caseworker";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }

  @Override
  public void configure(ConfigBuilder<ComponentHelperCaseData, State, Role> builder) {
    builder.jurisdiction("TEST", "Test", "Test");
    builder.caseType("COMPONENT_HELPERS", "Component helpers", "Component helpers");

    builder.event("componentHelpers")
        .forAllStates()
        .name("Component helpers")
        .fields()
        .field(ComponentHelperCaseData::getCaseFlags)
        .caseFlagBackingField()
        .done()
        .field(ComponentHelperCaseData::getFlagLauncher)
        .flagLauncherCreate()
        .done()
        .field(ComponentHelperCaseData::getCaseLinks)
        .componentLauncherBackingField("componentLauncher")
        .done()
        .field(ComponentHelperCaseData::getComponentLauncher)
        .componentLauncherUpdate("LinkedCases");
  }
}
