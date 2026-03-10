package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.ShowCondition;
import uk.gov.hmcts.ccd.sdk.api.Tab;

public class TabIfMethodsTest {

  @Test
  public void shouldApplyTypedConditionToTabFieldAndLabel() {
    ShowCondition condition = ShowCondition.stateIs("Open");

    Tab<TestData, TestRole> tab = Tab.TabBuilder.<TestData, TestRole>builder(TestData.class, new PropertyUtils())
        .tabID("tab")
        .labelText("Tab")
        .forRoles(TestRole.USER)
        .fieldIf(TestData::getName, condition)
        .labelIf("customLabel", condition, "Label")
        .build();

    assertThat(tab.getFields()).hasSize(2);
    assertThat(tab.getFields().get(0).getShowCondition()).isEqualTo("[STATE]=\"Open\"");
    assertThat(tab.getFields().get(1).getShowCondition()).isEqualTo("[STATE]=\"Open\"");
  }

  @Test
  public void shouldAddLabelWithoutShowCondition() {
    Tab<TestData, TestRole> tab = Tab.TabBuilder.<TestData, TestRole>builder(TestData.class, new PropertyUtils())
        .tabID("tab")
        .labelText("Tab")
        .forRoles(TestRole.USER)
        .label("customLabel", "Label")
        .build();

    assertThat(tab.getFields()).hasSize(1);
    assertThat(tab.getFields().get(0).getShowCondition()).isNull();
    assertThat(tab.getFields().get(0).getLabel()).isEqualTo("Label");
  }

  private static class TestData {
    private String name;

    public String getName() {
      return name;
    }
  }

  private enum TestRole implements HasRole {
    USER("caseworker-user");

    private final String role;

    TestRole(String role) {
      this.role = role;
    }

    @Override
    public String getRole() {
      return role;
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRU";
    }
  }
}
