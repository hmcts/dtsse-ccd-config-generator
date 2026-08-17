package uk.gov.hmcts.ccd.sdk.api.noc;

public enum NocError {

  ANSWERS_EMPTY("answers-empty", "Challenge question answers can not be empty"),
  ANSWERS_MISMATCH_QUESTIONS(
      "answers-mismatch-questions",
      "The number of provided answers must match the number of questions - expected %s answers, received %s"
  ),
  NO_ANSWER_PROVIDED_FOR_QUESTION(
      "no-answer-provided-for-question",
      "No answer has been provided for question ID '%s'"
  ),
  ANSWERS_NOT_MATCHED_ANY_LITIGANT(
      "answers-not-matched-any-litigant",
      "The answers did not match those for any litigant"
  ),
  ANSWERS_NOT_IDENTIFY_LITIGANT(
      "answers-not-identify-litigant",
      "The answers did not uniquely identify a litigant"
  ),
  REQUESTING_ORG_ALREADY_REPRESENTS_PARTY(
      "requesting-org-already-represents-party",
      "The requesting organisation already represents the matched party"
  );

  private final String code;
  private final String message;

  NocError(String code, String message) {
    this.code = code;
    this.message = message;
  }

  public String code() {
    return code;
  }

  public String message(Object... args) {
    return args == null || args.length == 0 ? message : message.formatted(args);
  }
}
