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
  public void validPrefixGeneratesLiteralFieldIds() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new ValidDtoConfig());
    File output = tmp.newFolder("dto-field-ids");

    new CaseFieldGenerator<TestCaseData, TestState, TestRole>().write(output, resolved);

    String caseFields = FileUtils.readFileToString(new File(output, "CaseField.json"), StandardCharsets.UTF_8);
    assertThat(caseFields).contains("cpc_propertyAddress");
  }

  @Test
  public void validPrefixPrefixesEventComplexTypeSheets() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new ValidComplexDtoConfig());
    File output = tmp.newFolder("dto-complex-field-ids");

    new CaseEventToComplexTypesGenerator<TestCaseData, TestState, TestRole>().write(output, resolved);

    File complexTypeSheet = new File(output, "CaseEventToComplexTypes/createClaim/cpc_propertyAddress.json");
    assertThat(complexTypeSheet).exists();
    assertThat(FileUtils.readFileToString(complexTypeSheet, StandardCharsets.UTF_8))
        .contains("\"CaseFieldID\": \"cpc_propertyAddress\"")
        .contains("\"ListElementCode\": \"AddressLine1\"");
  }

  @Test
  public void rejectsInvalidPrefixSyntax() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new InvalidPrefixConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid_case")
        .hasMessageContaining("validationEvent")
        .hasMessageContaining("TEST_CASE");
  }

  @Test
  public void rejectsPrefixCollisionsWithinCaseType() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new CollidingPrefixConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collision")
        .hasMessageContaining("firstEvent")
        .hasMessageContaining("secondEvent");
  }

  @Test
  public void overlappingPrefixesRemainDistinctWhenSeparatorIsPresent() throws IOException {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new OverlappingPrefixConfig());
    File output = tmp.newFolder("dto-overlapping-prefixes");

    new CaseFieldGenerator<TestCaseData, TestState, TestRole>().write(output, resolved);

    String caseFields = FileUtils.readFileToString(new File(output, "CaseField.json"), StandardCharsets.UTF_8);
    assertThat(caseFields).contains("a_propertyAddress");
    assertThat(caseFields).contains("ab_propertyAddress");
  }

  @Test
  public void rejectsGeneratedFieldIdCollisionsWithExistingCaseFields() {
    ResolvedCCDConfig<TestCaseData, TestState, TestRole> resolved = resolve(new CaseFieldCollisionConfig());

    assertThatThrownBy(() -> new CaseFieldGenerator<TestCaseData, TestState, TestRole>()
        .write(tmp.getRoot(), resolved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("collision")
        .hasMessageContaining("createClaim.bc")
        .hasMessageContaining("case field 'a_bc'")
        .hasMessageContaining("a_bc");
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
        .containsEntry("claimDetails", "cpc_showCrossBorderPage=\"Yes\"");
    assertThat(event.getFields().getPageLabels())
        .containsEntry("claimSummary", "Fee ${cpc_feeAmount}");

    List<String> fieldShowConditions = event.getFields().getFields().stream()
        .map(builder -> builder.build().getShowCondition())
        .toList();
    assertThat(fieldShowConditions)
        .contains("cpc_showCrossBorderPage=\"Yes\"");

    List<String> caseEventFieldLabels = event.getFields().getFields().stream()
        .map(builder -> builder.build().getCaseEventFieldLabel())
        .toList();
    assertThat(caseEventFieldLabels)
        .contains("Fee ${cpc_feeAmount}");

    List<String> explicitLabelValues = event.getFields().getExplicitFields().stream()
        .map(builder -> builder.build().getLabel())
        .toList();
    assertThat(explicitLabelValues)
        .contains("Cross border: ${cpc_showCrossBorderPage}");
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
        String fieldPrefix
    ) {
      builder.decentralisedEvent(eventId, dtoClass, fieldPrefix, submit(), start())
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
              "cpc",
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

  private static class InvalidPrefixConfig extends BaseDtoConfig {
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
              "cpc",
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

  private static class CollidingPrefixConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "firstEvent", ValidDto.class, "cpc");
      addDtoEvent(builder, "secondEvent", ValidDto.class, "cpc");
    }
  }

  private static class OverlappingPrefixConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "firstEvent", ValidDto.class, "a");
      addDtoEvent(builder, "secondEvent", ValidDto.class, "ab");
    }
  }

  private static class CaseFieldCollisionConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "createClaim", BcDto.class, "a");
    }
  }

  private static class LongFieldIdConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.decentralisedEvent(
              "longFieldEvent",
              LongFieldIdDto.class,
              "citizenapplicationupdate",
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
      addDtoEvent(builder, "jsonUnwrappedEvent", JsonUnwrappedDto.class, "cpr");
    }
  }

  private static class NestedCustomTypeConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "nestedTypeEvent", NestedCustomTypeDto.class, "cpr");
    }
  }

  private static class RecursiveCollectionConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      addDtoEvent(builder, "recursiveCollectionEvent", RecursiveCollectionDto.class, "cpr");
    }
  }

  private static class RewritingConfig extends BaseDtoConfig {
    @Override
    protected void configureEvent(DecentralisedConfigBuilder<TestCaseData, TestState, TestRole> builder) {
      builder.decentralisedEvent(
              "createClaim",
              RewritingDto.class,
              "cpc",
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
    private String a_bc;
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

  private static class BcDto {
    private String bc;
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
