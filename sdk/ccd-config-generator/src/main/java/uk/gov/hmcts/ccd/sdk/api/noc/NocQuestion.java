package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record NocQuestion(
    @JsonProperty("case_type_id") String caseTypeId,
    String order,
    @JsonProperty("question_text") String questionText,
    @JsonProperty("answer_field_type") AnswerFieldType answerFieldType,
    @JsonProperty("display_context_parameter") String displayContextParameter,
    @JsonProperty("challenge_question_id") String challengeQuestionId,
    @JsonProperty("answer_field") String answerField,
    @JsonProperty("question_id") String questionId
) {

  public static NocQuestion text(
      String caseTypeId,
      String order,
      String questionText,
      String challengeQuestionId,
      String questionId
  ) {
    return new NocQuestion(
        caseTypeId,
        order,
        questionText,
        AnswerFieldType.text(),
        "1",
        challengeQuestionId,
        null,
        questionId
    );
  }

  public record AnswerFieldType(
      String id,
      String type,
      Integer min,
      Integer max,
      @JsonProperty("regular_expression") String regularExpression,
      @JsonProperty("fixed_list_items") List<Object> fixedListItems,
      @JsonProperty("complex_fields") List<Object> complexFields,
      @JsonProperty("collection_field_type") Object collectionFieldType
  ) {

    public static AnswerFieldType text() {
      return new AnswerFieldType("Text", "Text", null, null, null, List.of(), List.of(), null);
    }
  }
}
