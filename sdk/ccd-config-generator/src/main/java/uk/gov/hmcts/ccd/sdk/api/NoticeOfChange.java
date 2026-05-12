package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.Lists;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStart;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;

@Data
public class NoticeOfChange<T, S, R extends HasRole> {

  public static final String DEFAULT_EVENT_ID = "notice-of-change-applied";
  public static final String DEFAULT_EVENT_NAME = "Notice of Change Applied";
  public static final String DEFAULT_REQUEST_EVENT_ID = "noc-request";
  public static final String DEFAULT_REQUEST_EVENT_NAME = "Notice of Change Request";
  public static final String CHECK_NOC_APPROVAL_URL = "${CCD_DEF_AAC_URL}/noc/check-noc-approval";

  private final List<ChallengeQuestion<T, R>> challenges = Lists.newArrayList();
  private String eventId = DEFAULT_EVENT_ID;
  private String eventName = DEFAULT_EVENT_NAME;
  private String requestEventId = DEFAULT_REQUEST_EVENT_ID;
  private String requestEventName = DEFAULT_REQUEST_EVENT_NAME;
  private final Set<S> forStates = new HashSet<>();
  private AboutToStart<T, S> aboutToStartCallback;
  private AboutToSubmit<T, S> aboutToSubmitCallback;
  private Submitted<T, S> submittedCallback;

  public static class NoticeOfChangeBuilder<T, S, R extends HasRole> {
    private final NoticeOfChange<T, S, R> noticeOfChange = new NoticeOfChange<>();
    private final Class<T> model;
    private final PropertyUtils propertyUtils;

    public NoticeOfChangeBuilder(Class<T> model, PropertyUtils propertyUtils) {
      this.model = model;
      this.propertyUtils = propertyUtils;
    }

    public ChallengeQuestion.ChallengeBuilder<T, S, R> challenge(String id) {
      ChallengeQuestion<T, R> challenge = new ChallengeQuestion<>(id);
      noticeOfChange.challenges.add(challenge);
      return new ChallengeQuestion.ChallengeBuilder<>(challenge, model, propertyUtils, this);
    }

    public NoticeOfChangeBuilder<T, S, R> eventId(String eventId) {
      noticeOfChange.eventId = eventId;
      return this;
    }

    public NoticeOfChangeBuilder<T, S, R> eventName(String eventName) {
      noticeOfChange.eventName = eventName;
      return this;
    }

    public NoticeOfChangeBuilder<T, S, R> requestEventId(String requestEventId) {
      noticeOfChange.requestEventId = requestEventId;
      return this;
    }

    public NoticeOfChangeBuilder<T, S, R> requestEventName(String requestEventName) {
      noticeOfChange.requestEventName = requestEventName;
      return this;
    }

    @SafeVarargs
    public final NoticeOfChangeBuilder<T, S, R> forStates(S... states) {
      for (S state : states) {
        noticeOfChange.forStates.add(state);
      }
      return this;
    }

    public NoticeOfChangeBuilder<T, S, R> aboutToStartCallback(AboutToStart<T, S> callback) {
      noticeOfChange.aboutToStartCallback = callback;
      return this;
    }

    public NoticeOfChangeBuilder<T, S, R> aboutToSubmitCallback(AboutToSubmit<T, S> callback) {
      noticeOfChange.aboutToSubmitCallback = callback;
      return this;
    }

    public NoticeOfChangeBuilder<T, S, R> submittedCallback(Submitted<T, S> callback) {
      noticeOfChange.submittedCallback = callback;
      return this;
    }

    public NoticeOfChange<T, S, R> build() {
      return noticeOfChange;
    }
  }
}
