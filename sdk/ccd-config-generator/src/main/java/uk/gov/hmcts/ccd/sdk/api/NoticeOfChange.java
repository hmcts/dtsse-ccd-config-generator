package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.Lists;
import java.util.List;
import lombok.Data;

@Data
public class NoticeOfChange<T, R extends HasRole> {

  private final List<ChallengeQuestion<T, R>> challenges = Lists.newArrayList();

  public static class NoticeOfChangeBuilder<T, R extends HasRole> {
    private final NoticeOfChange<T, R> noticeOfChange = new NoticeOfChange<>();
    private final Class<T> model;
    private final PropertyUtils propertyUtils;

    public NoticeOfChangeBuilder(Class<T> model, PropertyUtils propertyUtils) {
      this.model = model;
      this.propertyUtils = propertyUtils;
    }

    public ChallengeQuestion.ChallengeBuilder<T, R> challenge(String id) {
      ChallengeQuestion<T, R> challenge = new ChallengeQuestion<>(id);
      noticeOfChange.challenges.add(challenge);
      return new ChallengeQuestion.ChallengeBuilder<>(challenge, model, propertyUtils);
    }

    public NoticeOfChange<T, R> build() {
      return noticeOfChange;
    }
  }
}
