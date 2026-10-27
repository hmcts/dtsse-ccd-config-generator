package uk.gov.hmcts.example.enums;

/**
 * A team enum declaring MORE constants than the definition's list has codes — sscs's
 * {@code EventType} (261 constants against a 15-code {@code eventType}) and its
 * {@code HmcHearingType} (3 against 2). The enum is the team's own domain vocabulary, of which the
 * definition's list is a subset; {@code FixedListGenerator} emits one row per constant with no
 * filter, so pinning the list's ID onto this enum would emit every extra constant as a row the
 * definition does not have.
 *
 * <p>{@link uk.gov.hmcts.example.model.CaseData#oversized} declares it, so the pin would be reached
 * by declaration — which is exactly the binding {@code RetrofitTypeBinder} must refuse.
 */
public enum TeamOwnVocabulary {

  FIRST_CODE,

  SECOND_CODE,

  /** Team vocabulary the definition's list does not carry. */
  NOT_IN_THE_DEFINITION,

  /** A second extra, so the excess cannot be mistaken for an off-by-one. */
  ALSO_NOT_IN_THE_DEFINITION
}
