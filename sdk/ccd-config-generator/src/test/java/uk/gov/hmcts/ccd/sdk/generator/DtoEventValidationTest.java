package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
  public void validNamespaceGeneratesNamespaceDerivedFieldIds() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new ValidDtoConfig());
    File output = tmp.newFolder("dto-field-ids");

    new CaseFieldGenerator<TestCaseData, TestState, TestRole>().write(output, resolved);

    String caseFields = FileUtils.readFileToString(new File(output, "CaseField.json"), StandardCharsets.UTF_8);
    assertThat(caseFields).contains("claimCreatePropertyAddress");
  }

  @Test
  public void validNamespacePrefixesEventComplexTypeSheets() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new ValidComplexDtoConfig());
    File output = tmp.newFolder("dto-complex-field-ids");

    new CaseEventToComplexTypesGenerator<TestCaseData, TestState, TestRole>().write(output, resolved);

    File complexTypeSheet = new File(output, "CaseEventToComplexTypes/createClaim/claimCreatePropertyAddress.json");
    assertThat(complexTypeSheet).exists();
    assertThat(FileUtils.readFileToString(complexTypeSheet, StandardCharsets.UTF_8))
        .contains("\"CaseFieldID\": \"claimCreatePropertyAddress\"")
        .contains("\"ListElementCode\": \"AddressLine1\"");
  }

  @Test
  public void rejectsInvalidNamespaceSyntax() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new InvalidNamespaceConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid_case")
        .hasMessageContaining("validationEvent")
        .hasMessageContaining("TEST_CASE");
  }

  @Test
  public void rejectsNamespaceCollisionsWithinCaseType() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new CollidingNamespaceConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collision")
        .hasMessageContaining("firstEvent")
        .hasMessageContaining("secondEvent");
  }

  @Test
  public void rejectsGeneratedFieldIdsOverLimit() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new LongFieldIdConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds 70 characters")
        .hasMessageContaining("extremelyVerboseAndUnnecessarilyLongFieldNameForValidation");
  }

  @Test
  public void rejectsJsonUnwrappedFieldsInDto() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new JsonUnwrappedDtoConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("@JsonUnwrapped");
  }

  @Test
  public void rejectsUnsupportedNestedCustomTypes() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new NestedCustomTypeConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nested/custom types are not supported")
        .hasMessageContaining("address");
  }

  @Test
  public void recursivelyValidatesCollectionElementTypes() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new RecursiveCollectionConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("collection element type")
        .hasMessageContaining("history");
  }

  @Test
  public void rewritesSimpleDtoFieldReferencesInConditionsAndLabels() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new RewritingConfig());

    Event<TestCaseData, TestRole, TestState> event = resolved.getEvents().get("createClaim");

    assertThat(event.getFields().getPageShowConditions())
        .containsEntry("claimDetails", "claimCreateShowCrossBorderPage=\"Yes\"");
    assertThat(event.getFields().getPageLabels())
        .containsEntry("claimSummary", "Fee ${claimCreateFeeAmount}");

    List<String> fieldShowConditions = event.getFields().getFields().stream()
        .map(builder -> builder.build().getShowCondition())
        .toList();
    assertThat(fieldShowConditions)
        .contains("claimCreateShowCrossBorderPage=\"Yes\"");

    List<String> caseEventFieldLabels = event.getFields().getFields().stream()
        .map(builder -> builder.build().getCaseEventFieldLabel())
        .toList();
    assertThat(caseEventFieldLabels)
        .contains("Fee ${claimCreateFeeAmount}");

    List<String> explicitLabelValues = event.getFields().getExplicitFields().stream()
        .map(builder -> builder.build().getLabel())
        .toList();
    assertThat(explicitLabelValues)
        .contains("Cross border: ${claimCreateShowCrossBorderPage}");
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

    protected <D> void addDtoEvent(
        DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder,
        String eventId,
        Class<D> dtoClass,
        String fieldNamespace
    ) {
      builder.decentralisedEvent(eventId, dtoClass, fieldNamespace, submit(), start())
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .done();
    }

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
              "claim.create",
              submit(),
              start()
          )
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .optional(ValidDto::getPropertyAddress)
          .done();
    }
  }

  private static class InvalidNamespaceConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "validationEvent", ValidDto.class, "invalid_case");
    }
  }

  private static class ValidComplexDtoConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.decentralisedEvent(
              "createClaim",
              ValidComplexDto.class,
              "claim.create",
              submit(),
              start()
          )
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .complex(ValidComplexDto::getPropertyAddress)
              .mandatory(AddressUK::getAddressLine1)
          .done()
          .done();
    }
  }

  private static class CollidingNamespaceConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "firstEvent", ValidDto.class, "claim.create");
      addDtoEvent(builder, "secondEvent", ValidDto.class, "claim.create");
    }
  }

  private static class LongFieldIdConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.decentralisedEvent(
              "longFieldEvent",
              LongFieldIdDto.class,
              "citizen.application.update",
              submit(),
              start()
          )
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .optional(LongFieldIdDto::getExtremelyVerboseAndUnnecessarilyLongFieldNameForValidation)
          .done();
    }
  }

  private static class JsonUnwrappedDtoConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "jsonUnwrappedEvent", JsonUnwrappedDto.class, "claim.resume");
    }
  }

  private static class NestedCustomTypeConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "nestedTypeEvent", NestedCustomTypeDto.class, "claim.resume");
    }
  }

  private static class RecursiveCollectionConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "recursiveCollectionEvent", RecursiveCollectionDto.class, "claim.resume");
    }
  }

  private static class RewritingConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.decentralisedEvent(
              "createClaim",
              RewritingDto.class,
              "claim.create",
              submit(),
              start()
          )
          .initialState(TestState.DRAFT)
          .grant(CRU, TestRole.USER)
          .fields()
          .page("claimDetails")
          .showCondition("showCrossBorderPage=\"Yes\"")
          .optionalWithoutDefaultValue(
              RewritingDto::getFeeAmount,
              "showCrossBorderPage=\"Yes\"",
              "Fee ${feeAmount}"
          )
          .page("claimSummary")
          .pageLabel("Fee ${feeAmount}")
          .label("info", "Cross border: ${showCrossBorderPage}", "showCrossBorderPage=\"Yes\"")
          .done();
    }
  }

  private static class TestCaseData {
  }

  private static class ValidDto {
    private String propertyAddress;

    public String getPropertyAddress() {
      return propertyAddress;
    }
  }

  private static class LongFieldIdDto {
    private String extremelyVerboseAndUnnecessarilyLongFieldNameForValidation;

    public String getExtremelyVerboseAndUnnecessarilyLongFieldNameForValidation() {
      return extremelyVerboseAndUnnecessarilyLongFieldNameForValidation;
    }
  }

  private static class ValidComplexDto {
    private AddressUK propertyAddress;

    public AddressUK getPropertyAddress() {
      return propertyAddress;
    }
  }

  private static class JsonUnwrappedDto {
    @JsonUnwrapped
    private ValidDto address;
  }

  private static class NestedCustomTypeDto {
    private AddressDto address;
  }

  private static class RecursiveCollectionDto {
    private List<List<Map<String, String>>> history;
  }

  private static class RewritingDto {
    private String feeAmount;
    private String showCrossBorderPage;

    public String getFeeAmount() {
      return feeAmount;
    }

    public String getShowCrossBorderPage() {
      return showCrossBorderPage;
    }
  }

  private static class AddressDto {
    private String line1;
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
