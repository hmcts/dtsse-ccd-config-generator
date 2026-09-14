package uk.gov.hmcts.ccd.sdk.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.Test;

/**
 * Guards the contract between this generator and ccd-definition-store-api's
 * {@code ChallengeQuestionValidator.ANSWER_FIELD_MATCHER}. The importer rejects the whole
 * definition on a mismatch, so a change here that the store has not accepted yet is a
 * deployment-ordering failure rather than a test failure.
 *
 * <p>The two patterns below are copied from that validator. They can drift; the value is that a
 * change to role formatting fails here rather than during an environment's definition import.
 */
public class ChallengeQuestionAnswerContractTest {

  /** The pattern definition-store accepts today: role must be bracketed. */
  private static final String STORE_MATCHER_BRACKETED_ONLY =
      "^\\$\\{\\S.{1,}.\\S.{1,}}$|^\\$\\{\\S.{1,}.\\S.{1,}}:\\[\\S{1,}\\]$";

  /** The relaxed pattern from ccd-definition-store-api#1861, which also accepts unbracketed. */
  private static final String STORE_MATCHER_UNBRACKETED_ALLOWED =
      "^\\$\\{\\S.{1,}.\\S.{1,}}$|^\\$\\{\\S.{1,}.\\S.{1,}}:\\S{1,}$";

  private static final List<String> DEFAULT_PATH_ANSWERS = List.of(
      "${caseName}:[caseworker-publiclaw-solicitor]",
      "${allocatedJudge.judgeFullName}:[caseworker-publiclaw-courtadmin]",
      "${caseName}:[SOLICITOR]");

  @Test
  public void defaultAnswersAreAcceptedByTheStoreAsItIsDeployedToday() {
    for (String answer : DEFAULT_PATH_ANSWERS) {
      assertThat(answer)
          .as("default .answer() output must import without ccd-definition-store-api#1861: %s",
              answer)
          .matches(STORE_MATCHER_BRACKETED_ONLY);
    }
  }

  @Test
  public void answerAsDeclaredRequiresTheRelaxedStorePattern() {
    String asDeclared = "${hearingPreferencesWelsh}:caseworker-publiclaw-solicitor";

    assertThat(asDeclared)
        .as("answerAsDeclared output is rejected by the store until #1861 is deployed")
        .doesNotMatch(STORE_MATCHER_BRACKETED_ONLY);

    assertThat(asDeclared)
        .as("answerAsDeclared output is accepted once #1861 is deployed")
        .matches(STORE_MATCHER_UNBRACKETED_ALLOWED);
  }

  @Test
  public void everyAnswerWeEmitIsAcceptedByTheRelaxedPattern() {
    for (String answer : DEFAULT_PATH_ANSWERS) {
      assertThat(answer).matches(STORE_MATCHER_UNBRACKETED_ALLOWED);
    }
  }
}
