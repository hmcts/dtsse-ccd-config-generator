package uk.gov.hmcts.ccd.sdk.testing;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

import static org.assertj.core.api.Assertions.assertThat;

@CcdSdkTest(components = {
    CcdSdkTestIntegrationTest.BaseConfig.class,
    CcdSdkTestIntegrationTest.ReadOnlyEvent.class,
    CcdSdkTestIntegrationTest.TestView.class
})
class CcdSdkTestIntegrationTest {

  @Autowired
  private CcdEventTestSupport<TestCase, TestState> events;

  @Test
  void focusedContextSubmitsAndProjectsAnEvent() {
    long reference = events.seed(TestState.Open, new TestCase("stored"));
    var before = events.snapshot(reference);

    var result = events.event(reference, "readOnly", new TestCase("submitted"))
        .submitExpectingSuccess();

    assertThat(result.projectedCase().value()).isEqualTo("stored (view)");
    assertThat(result.rawData()).isEqualTo(before.rawData());
    assertThat(result.blobVersion()).isEqualTo(before.blobVersion());
    assertThat(result.caseRevision()).isEqualTo(1);
  }

  record TestCase(String value) {
  }

  enum TestState {
    Open
  }

  enum TestRole implements HasRole {
    User;

    @Override
    public String getRole() {
      return "caseworker";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }

  @Component
  static class BaseConfig implements CCDConfig<TestCase, TestState, TestRole> {
    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<TestCase, TestState, TestRole> builder) {
      builder.caseType("TestCase", "TestCase", "TestCase");
      builder.jurisdiction("TEST", "Test", "Test");
    }
  }

  @Component
  static class ReadOnlyEvent implements CCDConfig<TestCase, TestState, TestRole> {
    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<TestCase, TestState, TestRole> builder) {
      builder.decentralisedEvent("readOnly", payload -> SubmitResponse.defaultResponse()).forAllStates();
    }
  }

  @Component
  static class TestView implements CaseView<TestCase, TestState> {
    @Override
    public Set<String> caseTypeIds() {
      return Set.of("TestCase");
    }

    @Override
    public TestCase getCase(CaseViewRequest<TestState> request, TestCase blobCase) {
      return new TestCase(blobCase.value() + " (view)");
    }
  }
}
