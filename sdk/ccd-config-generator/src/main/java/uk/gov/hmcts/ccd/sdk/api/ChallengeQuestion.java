package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.Lists;
import java.util.List;
import lombok.Data;

@Data
public class ChallengeQuestion<T, R extends HasRole> {

  private final String id;
  private final List<ChallengeQuestionField<R>> questions = Lists.newArrayList();

  public static class ChallengeBuilder<T, S, R extends HasRole> {
    private final ChallengeQuestion<T, R> challenge;
    private final Class<T> model;
    private final PropertyUtils propertyUtils;
    private final NoticeOfChange.NoticeOfChangeBuilder<T, S, R> noticeOfChangeBuilder;

    public ChallengeBuilder(ChallengeQuestion<T, R> challenge, Class<T> model,
                            PropertyUtils propertyUtils,
                            NoticeOfChange.NoticeOfChangeBuilder<T, S, R> noticeOfChangeBuilder) {
      this.challenge = challenge;
      this.model = model;
      this.propertyUtils = propertyUtils;
      this.noticeOfChangeBuilder = noticeOfChangeBuilder;
    }

    public ChallengeQuestionField.QuestionBuilder<T, S, R> question(String questionId, String questionText) {
      ChallengeQuestionField<R> question = new ChallengeQuestionField<>(
          questionId, questionText, challenge.questions.size() + 1);
      challenge.questions.add(question);
      return new ChallengeQuestionField.QuestionBuilder<>(this, question, model, propertyUtils);
    }

    public ChallengeBuilder<T, S, R> challenge(String id) {
      return noticeOfChangeBuilder.challenge(id);
    }

    public NoticeOfChange.NoticeOfChangeBuilder<T, S, R> noticeOfChange() {
      return noticeOfChangeBuilder;
    }

    public NoticeOfChange.NoticeOfChangeBuilder<T, S, R> aboutToStartCallback(
        uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart<T, S> callback) {
      return noticeOfChangeBuilder.aboutToStartCallback(callback);
    }

    public NoticeOfChange.NoticeOfChangeBuilder<T, S, R> aboutToSubmitCallback(
        uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit<T, S> callback) {
      return noticeOfChangeBuilder.aboutToSubmitCallback(callback);
    }

    public NoticeOfChange.NoticeOfChangeBuilder<T, S, R> submittedCallback(
        uk.gov.hmcts.ccd.sdk.api.callback.Submitted<T, S> callback) {
      return noticeOfChangeBuilder.submittedCallback(callback);
    }
  }
}
