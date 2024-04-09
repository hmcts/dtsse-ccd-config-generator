package uk.gov.hmcts.ccd.sdk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ChallengeQuestion {
  @Builder.Default
  @JsonProperty("LiveFrom")
  private String liveFrom = "01/04/24";
  @Builder.Default
  @JsonProperty("ID")
  private String id = "NoCChallenge";
  @JsonProperty("DisplayOrder")
  private int displayOrder;
  @JsonProperty("QuestionText")
  private String questionText;
  @JsonProperty("AnswerFieldType")
  private String answerFieldType;
  @JsonProperty("Answer")
  private String answer;
  @JsonProperty("QuestionId")
  private String questionId;
  @JsonProperty("CaseTypeID")
  private String caseTypeID;


}
