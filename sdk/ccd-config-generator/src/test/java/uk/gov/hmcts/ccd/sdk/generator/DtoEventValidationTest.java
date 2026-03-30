package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.Start;
import uk.gov.hmcts.ccd.sdk.api.callback.Submit;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;

public class DtoEventValidationTest {

  @ClassRule
  public static TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void dtoEventGeneratesSinglePayloadField() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new ValidDtoConfig());
    File output = tmp.newFolder("dto-payload-field");

    new CaseFieldGenerator<TestCaseData, TestState, TestRole>().write(output, resolved);

    String caseFields = FileUtils.readFileToString(new File(output, "CaseField.json"), StandardCharsets.UTF_8);
    assertThat(caseFields).contains("\"payload\"");
    assertThat(caseFields).contains("TextArea");
    // Should NOT contain prefixed field IDs
    assertThat(caseFields).doesNotContain("cpc_propertyAddress");
  }

  @Test
  public void multipleDtoEventsGenerateSinglePayloadField() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new MultipleDtoConfig());
    File output = tmp.newFolder("dto-multiple-payload");

    new CaseFieldGenerator<TestCaseData, TestState, TestRole>().write(output, resolved);

    String caseFields = FileUtils.readFileToString(new File(output, "CaseField.json"), StandardCharsets.UTF_8);
    // Only one payload field, not multiple
    int payloadCount = caseFields.split("\"payload\"", -1).length - 1;
    assertThat(payloadCount).isEqualTo(1);
  }

  private ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolve(
      CCDConfig<TestCaseData, TestState, TestRole> config) {
    try {
      Class<?> resolverClass = Class.forName("uk.gov.hmcts.ccd.sdk.ConfigResolver");
      var constructor = resolverClass.getDeclaredConstructor(Collection.class);
      constructor.setAccessible(true);
      Object resolver = constructor.newInstance(List.of(config));
      var method = resolverClass.getDeclaredMethod("resolveCCDConfig");
      method.setAccessible(true);
      return (ResolvedCCDConfig<TestCaseData, TestState, TestRole>) method.invoke(resolver);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Unable to resolve CCD config for test", e);
    }
  }

  private abstract static class BaseDtoConfig implements CCDConfig<TestCaseData, TestState, TestRole> {
    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.caseType("TEST_CASE", "Test Case", "Test Case");
      configureEvent(builder);
    }

    protected abstract void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder);

    protected <D> Submit<D, TestState> submit() {
      return payload -> SubmitResponse.defaultResponse();
    }

    protected <D> Start<D, TestState> start() {
      return EventPayload::caseData;
    }
  }

  private static class ValidDtoConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.decentralisedEvent(
              "createClaim",
              ValidDto.class,
              submit(),
              start()
          )
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .done();
    }
  }

  private static class MultipleDtoConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.decentralisedEvent("firstEvent", ValidDto.class, submit(), start())
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .done();
      builder.decentralisedEvent("secondEvent", AnotherDto.class, submit(), start())
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .done();
    }
  }

  private static class TestCaseData {
    private String a_bc;
  }

  private static class ValidDto {
    private String propertyAddress;

    public String getPropertyAddress() {
      return propertyAddress;
    }
  }

  private static class AnotherDto {
    private String note;

    public String getNote() {
      return note;
    }
  }

  private enum TestState {
    DRAFT
  }

  private enum TestRole implements HasRole {
    USER;

    @Override
    public String getRole() {
      return "caseworker-test";
    }

    @Override
    public String getCaseTypePermissions() {
      return "CRUD";
    }
  }
}
