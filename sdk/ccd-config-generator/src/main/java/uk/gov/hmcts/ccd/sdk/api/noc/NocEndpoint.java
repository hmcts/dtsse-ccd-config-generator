package uk.gov.hmcts.ccd.sdk.api.noc;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

public final class NocEndpoint {

  public static final String XUI_WEBAPP_SERVICE = "xui_webapp";

  private final LongFunction<NocQuestionsResponse> questionHandler;
  private final Function<NocAnswersRequest, NocAnswersResponse> answerVerificationHandler;
  private final BiFunction<String, NocAnswersRequest, NocSubmissionResponse> submissionHandler;
  private final Set<String> authorisedServices;

  private NocEndpoint(Builder builder) {
    this.questionHandler = Objects.requireNonNull(builder.questionHandler, "questionHandler");
    this.answerVerificationHandler = Objects.requireNonNull(
        builder.answerVerificationHandler,
        "answerVerificationHandler"
    );
    this.submissionHandler = Objects.requireNonNull(builder.submissionHandler, "submissionHandler");
    this.authorisedServices = Set.copyOf(builder.authorisedServices);
  }

  public static Builder builder() {
    return new Builder();
  }

  public NocQuestionsResponse getQuestions(long caseId) {
    return questionHandler.apply(caseId);
  }

  public NocAnswersResponse verifyAnswers(NocAnswersRequest request) {
    return answerVerificationHandler.apply(request);
  }

  public NocSubmissionResponse submit(String authorisation, NocAnswersRequest request) {
    return submissionHandler.apply(authorisation, request);
  }

  public boolean isAuthorisedService(String serviceName) {
    return serviceName != null && authorisedServices.contains(normaliseServiceName(serviceName));
  }

  public static final class Builder {

    private LongFunction<NocQuestionsResponse> questionHandler;
    private Function<NocAnswersRequest, NocAnswersResponse> answerVerificationHandler;
    private BiFunction<String, NocAnswersRequest, NocSubmissionResponse> submissionHandler;
    private Set<String> authorisedServices = Set.of(XUI_WEBAPP_SERVICE);

    private Builder() {
    }

    public Builder questions(LongFunction<NocQuestionsResponse> handler) {
      this.questionHandler = handler;
      return this;
    }

    public Builder verifyAnswers(Function<NocAnswersRequest, NocAnswersResponse> handler) {
      this.answerVerificationHandler = handler;
      return this;
    }

    public Builder submit(BiFunction<String, NocAnswersRequest, NocSubmissionResponse> handler) {
      this.submissionHandler = handler;
      return this;
    }

    public Builder authorisedServices(String... services) {
      this.authorisedServices = Set.of(services).stream()
          .map(NocEndpoint::normaliseServiceName)
          .collect(Collectors.toUnmodifiableSet());
      return this;
    }

    public NocEndpoint build() {
      return new NocEndpoint(this);
    }
  }

  private static String normaliseServiceName(String serviceName) {
    return serviceName.trim().toLowerCase();
  }
}
