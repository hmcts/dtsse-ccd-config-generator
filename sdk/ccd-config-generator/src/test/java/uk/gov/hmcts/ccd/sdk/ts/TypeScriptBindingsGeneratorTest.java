package uk.gov.hmcts.ccd.sdk.ts;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.Collection;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

public class TypeScriptBindingsGeneratorTest {

  @ClassRule
  public static TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void generatesTypedBindingsForDtoEventsOnly() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new DtoConfig());

    File output = tmp.newFolder("ts-bindings-dto");
    TypeScriptBindingsGenerator generator = new TypeScriptBindingsGenerator();
    generator.writeBindings(output, resolved, "test-module");

    File contractsFile = new File(output, "event-contracts.ts");
    File dtoFile = new File(output, "dto-types.ts");

    assertThat(contractsFile).exists();
    assertThat(dtoFile).exists();
    assertThat(new File(output, "client.ts")).doesNotExist();

    String contracts = FileUtils.readFileToString(contractsFile, StandardCharsets.UTF_8);
    assertThat(contracts).contains("\"createWidget\"");
    assertThat(contracts).doesNotContain("legacyUpdate");
    assertThat(contracts).contains("defineCaseBindings");
    assertThat(contracts).contains("caseTypeId: \"ts_case\"");
    assertThat(contracts).doesNotContain("fieldPrefix");
    assertThat(contracts).doesNotContain("pages");
    assertThat(contracts).contains("as const satisfies CcdCaseBindings<EventDtoMap>");

    String firstDto = FileUtils.readFileToString(dtoFile, StandardCharsets.UTF_8);
    generator.writeBindings(output, resolved, "test-module");
    String secondDto = FileUtils.readFileToString(dtoFile, StandardCharsets.UTF_8);
    assertThat(firstDto).isEqualTo(secondDto);
  }

  @Test
  public void doesNotWriteBindingsWhenNoDtoEventsPresent() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new NonDtoConfig());

    File output = tmp.newFolder("ts-bindings-no-dto");
    TypeScriptBindingsGenerator generator = new TypeScriptBindingsGenerator();
    generator.writeBindings(output, resolved, "test-module");

    File[] generated = output.listFiles();
    assertThat(generated).isEmpty();
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

  private static class DtoConfig implements CCDConfig<TestCaseData, TestState, TestRole> {
    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.caseType("ts_case", "TS Case", "TS Case");
      builder.event("legacyUpdate")
          .forAllStates()
          .grant(CRU, TestRole.USER)
          .fields()
          .optional(TestCaseData::getLegacyField)
          .done();

      builder.decentralisedEvent(
              "createWidget",
              CreateWidgetDto.class,
              this::submitDto,
              this::startDto
          )
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .name("Create Widget")
          .fields()
          .done();
    }

    private CreateWidgetDto startDto(EventPayload<CreateWidgetDto, TestState> payload) {
      return payload.caseData();
    }

    private SubmitResponse<TestState> submitDto(EventPayload<CreateWidgetDto, TestState> payload) {
      return SubmitResponse.defaultResponse();
    }
  }

  private static class NonDtoConfig implements CCDConfig<TestCaseData, TestState, TestRole> {
    @Override
    public void configureDecentralised(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.caseType("ts_case", "TS Case", "TS Case");
      builder.event("legacyUpdate")
          .forAllStates()
          .grant(CRU, TestRole.USER)
          .fields()
          .optional(TestCaseData::getLegacyField)
          .done();
    }
  }

  private static class TestCaseData {
    private String legacyField;

    public String getLegacyField() {
      return legacyField;
    }
  }

  private static class CreateWidgetDto {
    private String name;
    private String reference;

    public String getName() {
      return name;
    }

    public String getReference() {
      return reference;
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
