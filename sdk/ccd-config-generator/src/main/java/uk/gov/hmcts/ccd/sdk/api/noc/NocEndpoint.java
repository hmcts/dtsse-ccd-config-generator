package uk.gov.hmcts.ccd.sdk.api.noc;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public final class NocEndpoint {

  public static final String XUI_WEBAPP_SERVICE = "xui_webapp";

  private final BiFunction<NocSubmitContext, NocAnswersRequest, NocAnswersResponse> validationHandler;
  private final BiFunction<NocSubmitContext, NocAnswersRequest, NocSubmissionResponse> submissionHandler;
  private final Set<String> authorisedServices;
  private final String caseTypeId;

  private NocEndpoint(Builder builder) {
    this.validationHandler = Objects.requireNonNull(builder.validationHandler, "validationHandler");
    this.submissionHandler = Objects.requireNonNull(builder.submissionHandler, "submissionHandler");
    this.authorisedServices = Set.copyOf(builder.authorisedServices);
    this.caseTypeId = builder.caseTypeId;
  }

  public static Builder builder() {
    return new Builder();
  }

  public NocAnswersResponse validate(NocSubmitContext context, NocAnswersRequest request) {
    return validationHandler.apply(context, request);
  }

  public NocSubmissionResponse submit(NocSubmitContext context, NocAnswersRequest request) {
    return submissionHandler.apply(context, request);
  }

  public boolean isAuthorisedService(String serviceName) {
    return serviceName != null && authorisedServices.contains(normaliseServiceName(serviceName));
  }

  public String caseTypeId() {
    return caseTypeId;
  }

  public static final class Builder {

    private BiFunction<NocSubmitContext, NocAnswersRequest, NocAnswersResponse> validationHandler;
    private BiFunction<NocSubmitContext, NocAnswersRequest, NocSubmissionResponse> submissionHandler;
    private Set<String> authorisedServices = Set.of(XUI_WEBAPP_SERVICE);
    private String caseTypeId;

    private Builder() {
    }

    public Builder validate(BiFunction<NocSubmitContext, NocAnswersRequest, NocAnswersResponse> handler) {
      this.validationHandler = handler;
      return this;
    }

    public Builder submit(BiFunction<NocSubmitContext, NocAnswersRequest, NocSubmissionResponse> handler) {
      this.submissionHandler = handler;
      return this;
    }

    public Builder authorisedServices(String... services) {
      this.authorisedServices = Set.of(services).stream()
          .map(NocEndpoint::normaliseServiceName)
          .collect(Collectors.toUnmodifiableSet());
      return this;
    }

    public Builder caseTypeId(String caseTypeId) {
      this.caseTypeId = caseTypeId;
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
