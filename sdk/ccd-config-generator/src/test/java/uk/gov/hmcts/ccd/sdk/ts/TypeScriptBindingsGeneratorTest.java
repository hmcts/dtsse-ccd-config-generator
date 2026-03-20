package uk.gov.hmcts.ccd.sdk;

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
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.ts.TypeScriptBindingsGenerator;

public class TypeScriptBindingsGeneratorTest {

  @ClassRule
  public static TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void generatesTypedBindingsForDtoEventsOnly() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved =
        new ConfigResolver<>(List.of(new DtoConfig())).resolveCCDConfig();

    File output = tmp.newFolder("ts-bindings-dto");
    TypeScriptBindingsGenerator generator = new TypeScriptBindingsGenerator();
    generator.writeBindings(output, resolved, "test-module");

    File contractsFile = new File(output, "event-contracts.ts");
    File dtoFile = new File(output, "dto-types.ts");
    File indexFile = new File(output, "index.ts");

    assertThat(contractsFile).exists();
    assertThat(dtoFile).exists();
    assertThat(new File(output, "client.ts")).doesNotExist();
    assertThat(indexFile).exists();

    String contracts = FileUtils.readFileToString(contractsFile, StandardCharsets.UTF_8);
    assertThat(contracts).contains("\"createWidget\"");
    assertThat(contracts).doesNotContain("legacyUpdate");
    assertThat(contracts).contains("defineCaseBindings");
    assertThat(contracts).contains("caseTypeId: \"ts_case\"");
    assertThat(contracts).contains("fieldPrefix: \"widget\"");
    assertThat(contracts).doesNotContain("fields: [");
    assertThat(contracts).contains("pages: [\"1\"]");
    assertThat(contracts).contains("as const satisfies CcdCaseBindings<EventDtoMap>");

    String firstDto = FileUtils.readFileToString(dtoFile, StandardCharsets.UTF_8);
    generator.writeBindings(output, resolved, "test-module");
    String secondDto = FileUtils.readFileToString(dtoFile, StandardCharsets.UTF_8);
    assertThat(firstDto).isEqualTo(secondDto);
  }

  @Test
  public void doesNotWriteBindingsWhenNoDtoEventsPresent() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved =
        new ConfigResolver<>(List.of(new NonDtoConfig())).resolveCCDConfig();

    File output = tmp.newFolder("ts-bindings-no-dto");
    TypeScriptBindingsGenerator generator = new TypeScriptBindingsGenerator();
    generator.writeBindings(output, resolved, "test-module");

    File[] generated = output.listFiles();
    assertThat(generated).isEmpty();
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
              "widget",
              this::submitDto,
              this::startDto
          )
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .name("Create Widget")
          .fields()
          .optional(CreateWidgetDto::getName)
          .optional(CreateWidgetDto::getReference)
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
