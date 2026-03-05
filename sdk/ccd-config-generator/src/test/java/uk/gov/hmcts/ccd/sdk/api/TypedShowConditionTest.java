package uk.gov.hmcts.ccd.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.Test;

public class TypedShowConditionTest {

  @Test
  public void shouldBuildConditionFromGetter() {
    String condition = TypedShowCondition.when(SampleData::getState).is("Submitted").toString();

    assertThat(condition).isEqualTo("state=\"Submitted\"");
  }

  @Test
  public void shouldUseJsonPropertyFromField() {
    String condition = TypedShowCondition.when(SampleData::getApplicantName).is("Alex").toString();

    assertThat(condition).isEqualTo("applicant_name=\"Alex\"");
  }

  @Test
  public void shouldUseEnumJsonValuesAndComposeConditions() {
    TypedShowCondition condition = TypedShowCondition.when(SampleData::getDecision).is(Decision.APPROVED)
        .and(TypedShowCondition.when(SampleData::getFlags).contains("urgent"))
        .or(TypedShowCondition.when(SampleData::getState).isAnyOf("Submitted", "Issued"));

    assertThat(condition.toString())
        .isEqualTo("decision=\"approved\" AND flags CONTAINS \"urgent\" OR state=\"Submitted\" OR state=\"Issued\"");
  }

  @Test
  public void shouldEscapeQuotesInValues() {
    String condition = TypedShowCondition.when(SampleData::getState).is("He said \"yes\"").toString();

    assertThat(condition).isEqualTo("state=\"He said \\\"yes\\\"\"");
  }

  @Test
  public void shouldResolveAcronymStyleGetterToLowerCamelFieldName() {
    String condition = TypedShowCondition.when(SampleData::getAField).is("YES").toString();

    assertThat(condition).isEqualTo("aField=\"YES\"");
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
}
