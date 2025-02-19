package uk.gov.hmcts.ccd.sdk;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.runtime.CallbackController;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class CallbackDispatcher implements CCDEventListener {
    private final CallbackController runtime;
    private Set<String> submittedCallbacks;
    private Set<String> aboutToSubmitCallbacks;

    @Autowired
    public CallbackDispatcher(CallbackController runtime, List<ResolvedCCDConfig<?, ?, ?>> configs) {
        this.runtime = runtime;
        var cfg = configs.stream().filter(x -> x.getCaseType().equals("PCS")).findFirst();
        submittedCallbacks = new HashSet<>();
        aboutToSubmitCallbacks = new HashSet<>();
        cfg.get().getEvents().forEach((x, y) -> {
            if (y.getAboutToSubmitCallback() != null) {
                aboutToSubmitCallbacks.add(x);
            }
            if (y.getSubmittedCallback() != null) {
                submittedCallbacks.add(x);
            }
        });
    }

    public boolean hasAboutToSubmitCallbackForEvent(String event) {
        return aboutToSubmitCallbacks.contains(event);
    }

  @Override
  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    return runtime.aboutToSubmit(request);
  }

  @Override
  public boolean hasSubmittedCallbackForEvent(String event) {
        return submittedCallbacks.contains(event);
    }
}
