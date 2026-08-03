package uk.gov.hmcts.ccd.sdk.api.noc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NocAnswersResponse(
    NocOrganisation organisation,
    @JsonProperty("status_message") String statusMessage,
    String status,
    String message,
    String code,
    List<String> errors
) {

  public static NocAnswersResponse verified(NocOrganisation organisation) {
    return new NocAnswersResponse(
        organisation,
        "Notice of Change answers verified successfully",
        null,
        null,
        null,
        null
    );
  }

  public static NocAnswersResponse invalid(NocError error, Object... args) {
    return invalid(error.code(), error.message(args));
  }

  public static NocAnswersResponse invalid(String code, String message) {
    return new NocAnswersResponse(null, null, "BAD_REQUEST", message, code, List.of());
  }

  public static NocAnswersResponse answersEmpty() {
    return invalid(NocError.ANSWERS_EMPTY);
  }

  public static NocAnswersResponse answersMismatchQuestions(int expected, int received) {
    return invalid(NocError.ANSWERS_MISMATCH_QUESTIONS, expected, received);
  }

  public static NocAnswersResponse noAnswerProvidedForQuestion(String questionId) {
    return invalid(NocError.NO_ANSWER_PROVIDED_FOR_QUESTION, questionId);
  }

  public static NocAnswersResponse answersNotMatchedAnyLitigant() {
    return invalid(NocError.ANSWERS_NOT_MATCHED_ANY_LITIGANT);
  }

  public static NocAnswersResponse answersNotIdentifyLitigant() {
    return invalid(NocError.ANSWERS_NOT_IDENTIFY_LITIGANT);
  }

  public static NocAnswersResponse requestingOrgAlreadyRepresentsParty() {
    return invalid(NocError.REQUESTING_ORG_ALREADY_REPRESENTS_PARTY);
  }

  @JsonIgnore
  public boolean isValid() {
    return code == null;
  }
}
