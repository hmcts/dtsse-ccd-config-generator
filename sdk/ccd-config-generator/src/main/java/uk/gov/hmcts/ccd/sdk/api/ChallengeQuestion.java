package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.Lists;
import java.util.List;
import lombok.Data;

@Data
public class ChallengeQuestion<T, R extends HasRole> {

  private final String id;
  private String liveFrom;
  private final List<ChallengeQuestionField<R>> questions = Lists.newArrayList();

  public static class ChallengeBuilder<T, R extends HasRole> {
    private final ChallengeQuestion<T, R> challenge;
    private final Class<T> model;
    private final PropertyUtils propertyUtils;

    public ChallengeBuilder(ChallengeQuestion<T, R> challenge, Class<T> model,
                            PropertyUtils propertyUtils) {
      this.challenge = challenge;
      this.model = model;
      this.propertyUtils = propertyUtils;
    }

    public ChallengeBuilder<T, R> liveFrom(String liveFrom) {
      challenge.liveFrom = liveFrom;
      return this;
    }

    public ChallengeQuestionField.QuestionBuilder<T, R> question(String questionId, String questionText) {
      ChallengeQuestionField<R> question = new ChallengeQuestionField<>(
          questionId, questionText, challenge.questions.size() + 1);
      challenge.questions.add(question);
      return new ChallengeQuestionField.QuestionBuilder<>(this, question, model, propertyUtils);
    }
  }
}
