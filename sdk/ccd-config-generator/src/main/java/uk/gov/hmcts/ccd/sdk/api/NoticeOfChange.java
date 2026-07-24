package uk.gov.hmcts.ccd.sdk.api;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.function.BiFunction;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersRequest;
import uk.gov.hmcts.ccd.sdk.api.noc.NocAnswersResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocEndpoint;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmissionResponse;
import uk.gov.hmcts.ccd.sdk.api.noc.NocSubmitContext;

@Data
public class NoticeOfChange<T, R extends HasRole> {

  private final List<ChallengeQuestion<T, R>> challenges = Lists.newArrayList();
  private NocEndpoint endpoint;

  public static class NoticeOfChangeBuilder<T, R extends HasRole> {
    private final NoticeOfChange<T, R> noticeOfChange = new NoticeOfChange<>();
    private final Class<T> model;
    private final PropertyUtils propertyUtils;
    private BiFunction<NocSubmitContext, NocAnswersRequest, NocAnswersResponse> validationHandler;
    private BiFunction<NocSubmitContext, NocAnswersRequest, NocSubmissionResponse> submissionHandler;

    public NoticeOfChangeBuilder(Class<T> model, PropertyUtils propertyUtils) {
      this.model = model;
      this.propertyUtils = propertyUtils;
    }

    public ChallengeQuestion.ChallengeBuilder<T, R> challenge(String id) {
      ChallengeQuestion<T, R> challenge = new ChallengeQuestion<>(id);
      noticeOfChange.challenges.add(challenge);
      return new ChallengeQuestion.ChallengeBuilder<>(challenge, model, propertyUtils);
    }

    public NoticeOfChangeBuilder<T, R> validate(
        BiFunction<NocSubmitContext, NocAnswersRequest, NocAnswersResponse> handler) {
      this.validationHandler = handler;
      return this;
    }

    public NoticeOfChangeBuilder<T, R> submit(
        BiFunction<NocSubmitContext, NocAnswersRequest, NocSubmissionResponse> handler) {
      this.submissionHandler = handler;
      return this;
    }

    public NoticeOfChange<T, R> build(String caseTypeId) {
      if ((validationHandler == null) != (submissionHandler == null)) {
        throw new IllegalStateException(
            "Notice of Change validation and submission handlers must both be configured"
        );
      }

      if (validationHandler != null) {
        noticeOfChange.endpoint = NocEndpoint.builder()
            .caseTypeId(caseTypeId)
            .validate(validationHandler)
            .submit(submissionHandler)
            .build();
      }
      return noticeOfChange;
    }
  }
}
