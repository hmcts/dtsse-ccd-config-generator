package uk.gov.hmcts.ccd.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.junit.Test;

public class ShowConditionTest {

  @Test
  public void shouldBuildConditionFromGetter() {
    String condition = ShowCondition.when(SampleData::getState).is("Submitted").toString();

    assertThat(condition).isEqualTo("state=\"Submitted\"");
  }

  @Test
  public void shouldUseJsonPropertyFromField() {
    String condition = ShowCondition.when(SampleData::getApplicantName).is("Alex").toString();

    assertThat(condition).isEqualTo("applicant_name=\"Alex\"");
  }

  @Test
  public void shouldUseEnumJsonValuesAndComposeConditions() {
    ShowCondition condition = ShowCondition.when(SampleData::getDecision).is(Decision.APPROVED)
        .and(ShowCondition.when(SampleData::getFlags).contains("urgent"))
        .or(ShowCondition.when(SampleData::getState).isAnyOf("Submitted", "Issued"));

    assertThat(condition.toString())
        .isEqualTo("decision=\"approved\" AND flagsCONTAINS\"urgent\" OR state=\"Submitted\" OR state=\"Issued\"");
  }

  @Test
  public void shouldEscapeQuotesInValues() {
    String condition = ShowCondition.when(SampleData::getState).is("He said \"yes\"").toString();

    assertThat(condition).isEqualTo("state=\"He said \\\"yes\\\"\"");
  }

  @Test
  public void shouldBuildNotEqualsConditionFromGetter() {
    String condition = ShowCondition.when(SampleData::getState).isNot("Deleted").toString();

    assertThat(condition).isEqualTo("state!=\"Deleted\"");
  }

  @Test
  public void shouldBuildStateConditions() {
    String condition = ShowCondition.stateIs("Open")
        .and(ShowCondition.stateIsNot(Decision.APPROVED))
        .toString();

    assertThat(condition).isEqualTo("[STATE]=\"Open\" AND [STATE]!=\"approved\"");
  }

  @Test
  public void shouldBuildConditionFromNamedField() {
    String condition = ShowCondition.field("custom_field")
        .contains(Decision.APPROVED)
        .toString();

    assertThat(condition).isEqualTo("custom_fieldCONTAINS\"approved\"");
  }

  @Test
  public void shouldBuildConditionFromNamedFieldUsingStaticContains() {
    String condition = ShowCondition.contains(ShowCondition.field("custom_field"), Decision.APPROVED).toString();

    assertThat(condition).isEqualTo("custom_fieldCONTAINS\"approved\"");
  }

  @Test
  public void shouldBuildConditionFromNamedFieldUsingStaticComparisonHelpers() {
    ShowCondition.NamedFieldCondition field = ShowCondition.field("custom_field");

    String condition = ShowCondition.is(field, "YES")
        .and(ShowCondition.isNot(field, "NO"))
        .or(ShowCondition.isAnyOf(field, "MAYBE", "LATER"))
        .toString();

    assertThat(condition).isEqualTo(
        "custom_field=\"YES\" AND custom_field!=\"NO\" OR custom_field=\"MAYBE\" OR custom_field=\"LATER\"");
  }

  @Test
  public void shouldBuildFieldRefFromGetter() {
    ShowCondition.FieldRef field = ShowCondition.ref(SampleData::getAField);

    assertThat(ShowCondition.is(field, "YES").toString()).isEqualTo("aField=\"YES\"");
  }

  @Test
  public void shouldBuildNestedFieldRefFromGetters() {
    ShowCondition.FieldRef field = ShowCondition.ref(OuterUnwrappedData::getWarrantDetails,
        WarrantData::getAnyRiskToBailiff);

    assertThat(ShowCondition.is(field, "YES").toString()).isEqualTo("warrantAnyRiskToBailiff=\"YES\"");
  }

  @Test
  public void shouldRejectNullFieldForStaticHelpers() {
    assertThatThrownBy(() -> ShowCondition.is(null, "YES"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("field must not be null");
  }

  @Test
  public void shouldRejectEmptyStaticIsAnyOfValues() {
    assertThatThrownBy(() -> ShowCondition.isAnyOf(ShowCondition.field("custom_field")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("values must not be empty");
  }

  @Test
  public void shouldComposeConditionsUsingAllOfAndAnyOf() {
    String condition = ShowCondition.allOf(
        ShowCondition.stateIs("Open"),
        ShowCondition.anyOf(
            ShowCondition.when(SampleData::getState).is("Submitted"),
            ShowCondition.when(SampleData::getState).is("Issued")))
        .toString();

    assertThat(condition).isEqualTo("[STATE]=\"Open\" AND (state=\"Submitted\" OR state=\"Issued\")");
  }

  @Test
  public void shouldRejectEmptyAllOf() {
    assertThatThrownBy(() -> ShowCondition.allOf())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("conditions must not be empty");
  }

  @Test
  public void shouldRejectNullConditionInAnyOf() {
    assertThatThrownBy(() -> ShowCondition.anyOf(ShowCondition.stateIs("Open"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("conditions must not contain null");
  }

  @Test
  public void shouldExposeNeverShowConstant() {
    assertThat(ShowCondition.NEVER_SHOW).isEqualTo("[STATE]=\"NEVER_SHOW\"");
  }

  @Test
  public void shouldResolveAcronymStyleGetterToLowerCamelFieldName() {
    String condition = ShowCondition.when(SampleData::getAField).is("YES").toString();

    assertThat(condition).isEqualTo("aField=\"YES\"");
  }

  @Test
  public void shouldResolveNestedFieldForJsonUnwrappedParent() {
    String condition = ShowCondition.when(OuterUnwrappedData::getWarrantDetails, WarrantData::getAnyRiskToBailiff)
        .is("YES")
        .toString();

    assertThat(condition).isEqualTo("warrantAnyRiskToBailiff=\"YES\"");
  }

  @Test
  public void shouldCapitalizeChildFieldWhenParentUsesUnwrappedPrefix() {
    String condition = ShowCondition.when(OuterNoticeData::getNoticeServedDetails, NoticeDetailsData::getNoticeDate)
        .is("2024-01-01")
        .toString();

    assertThat(condition).isEqualTo("notice_NoticeDate=\"2024-01-01\"");
  }

  @Test
  public void shouldResolveNestedFieldWithDotNotationWhenParentIsNotUnwrapped() {
    String condition = ShowCondition.when(OuterNestedData::getAdditionalReasonsForPossession,
            AdditionalReasonsData::getHasReasons)
        .is("Yes")
        .toString();

    assertThat(condition).isEqualTo("additionalReasonsForPossession.hasReasons=\"Yes\"");
  }

  @Test
  public void shouldApplyJsonNamingStrategyForNestedChildField() {
    String condition = ShowCondition.when(OuterNoRentData::getNoRentArrearsGroundsOptions,
        NoRentData::getMandatoryGrounds).contains("X").toString();

    assertThat(condition).isEqualTo("noRentArrears_MandatoryGroundsCONTAINS\"X\"");
  }

  @Test
  public void shouldResolveThreeLevelNestedFieldAcrossUnwrappedParents() {
    String condition = ShowCondition.when(EnforcementOrderData::getWarrantDetails,
        WarrantDetailsData::getLandRegistryFees, LandRegistryFeesData::getHaveLandRegistryFeesBeenPaid)
        .is("YES")
        .toString();

    assertThat(condition).isEqualTo("warrantHaveLandRegistryFeesBeenPaid=\"YES\"");
  }

  @Test
  public void shouldCapitalizeThreeLevelGrandChildFieldWhenUnwrappedPathIsFlat() {
    String condition = ShowCondition.when(EnforcementOrderLowerCamelData::getWritDetails,
        WritDetailsLowerCamelData::getStatementOfTruth, StatementOfTruthLowerCamelData::getCompletedBy)
        .is("CLAIMANT")
        .toString();

    assertThat(condition).isEqualTo("writCompletedBy=\"CLAIMANT\"");
  }

  @Test
  public void shouldResolveThreeLevelNestedFieldWhenUnwrappedChildUsesJsonPropertyAlias() {
    String condition = ShowCondition.when(AliasedEnforcementOrderData::getWarrantDetails,
        AliasedWarrantDetailsData::getStatementOfTruth, StatementOfTruthLowerCamelData::getCompletedBy)
        .is("CLAIMANT")
        .toString();

    assertThat(condition).isEqualTo("warrantCompletedBy=\"CLAIMANT\"");
  }

  private static class SampleData {

    @JsonProperty("applicant_name")
    private String applicantName;
    private String aField;
    private String state;
    private Decision decision;
    private String flags;

    public String getApplicantName() {
      return applicantName;
    }

    public String getState() {
      return state;
    }

    public String getAField() {
      return aField;
    }

    public Decision getDecision() {
      return decision;
    }

    public String getFlags() {
      return flags;
    }
  }

  private enum Decision {
    @JsonProperty("approved")
    APPROVED,
    REJECTED;

    @JsonValue
    public String getValue() {
      return name().toLowerCase();
    }
  }

  private static class OuterUnwrappedData {

    @JsonUnwrapped(prefix = "warrant")
    private WarrantData warrantDetails;

    public WarrantData getWarrantDetails() {
      return warrantDetails;
    }
  }

  private static class WarrantData {

    private String anyRiskToBailiff;

    public String getAnyRiskToBailiff() {
      return anyRiskToBailiff;
    }
  }

  private static class OuterNestedData {

    private AdditionalReasonsData additionalReasonsForPossession;

    public AdditionalReasonsData getAdditionalReasonsForPossession() {
      return additionalReasonsForPossession;
    }
  }

  private static class OuterNoticeData {

    @JsonUnwrapped(prefix = "notice_")
    private NoticeDetailsData noticeServedDetails;

    public NoticeDetailsData getNoticeServedDetails() {
      return noticeServedDetails;
    }
  }

  private static class NoticeDetailsData {

    private String noticeDate;

    public String getNoticeDate() {
      return noticeDate;
    }
  }

  private static class AdditionalReasonsData {

    private String hasReasons;

    public String getHasReasons() {
      return hasReasons;
    }
  }

  private static class OuterNoRentData {

    @JsonUnwrapped(prefix = "noRentArrears_")
    private NoRentData noRentArrearsGroundsOptions;

    public NoRentData getNoRentArrearsGroundsOptions() {
      return noRentArrearsGroundsOptions;
    }
  }

  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  private static class NoRentData {

    private String mandatoryGrounds;

    public String getMandatoryGrounds() {
      return mandatoryGrounds;
    }
  }

  private static class EnforcementOrderData {

    @JsonUnwrapped(prefix = "warrant")
    private WarrantDetailsData warrantDetails;

    public WarrantDetailsData getWarrantDetails() {
      return warrantDetails;
    }
  }

  private static class EnforcementOrderLowerCamelData {

    @JsonUnwrapped(prefix = "writ")
    private WritDetailsLowerCamelData writDetails;

    public WritDetailsLowerCamelData getWritDetails() {
      return writDetails;
    }
  }

  private static class WritDetailsLowerCamelData {

    @JsonUnwrapped
    private StatementOfTruthLowerCamelData statementOfTruth;

    public StatementOfTruthLowerCamelData getStatementOfTruth() {
      return statementOfTruth;
    }
  }

  private static class StatementOfTruthLowerCamelData {

    private String completedBy;

    public String getCompletedBy() {
      return completedBy;
    }
  }

  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  private static class WarrantDetailsData {

    @JsonUnwrapped
    private LandRegistryFeesData landRegistryFees;

    public LandRegistryFeesData getLandRegistryFees() {
      return landRegistryFees;
    }
  }

  @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
  private static class LandRegistryFeesData {

    private String haveLandRegistryFeesBeenPaid;

    public String getHaveLandRegistryFeesBeenPaid() {
      return haveLandRegistryFeesBeenPaid;
    }
  }

  private static class AliasedEnforcementOrderData {

    @JsonUnwrapped(prefix = "warrant")
    private AliasedWarrantDetailsData warrantDetails;

    public AliasedWarrantDetailsData getWarrantDetails() {
      return warrantDetails;
    }
  }

  private static class AliasedWarrantDetailsData {

    @JsonProperty("statement_alias")
    @JsonUnwrapped
    private StatementOfTruthLowerCamelData statementOfTruth;

    public StatementOfTruthLowerCamelData getStatementOfTruth() {
      return statementOfTruth;
    }
  }
}
