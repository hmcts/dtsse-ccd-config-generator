package uk.gov.hmcts.ccd.sdk;

import com.google.common.base.Strings;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.runtime.CallbackController;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

import java.util.Arrays;

@RequiredArgsConstructor
@Configuration
public class ConfigGeneratorCallbackDispatcher implements CCDEventListener {

    private final CallbackController controller;

    public boolean hasAboutToSubmitCallbackForEvent(String caseType, String event) {
        return controller.hasAboutToSubmitCallback(caseType, event);
    }

    @SneakyThrows
    @Override
    public String nameForState(String caseType, String stateId) {
        // TODO: Refactor the config generator to reuse label extraction
        var resolved = controller.getCaseTypeToConfig().get(caseType);
        var clazz = resolved.getStateClass();
        var enumType = Arrays.stream(clazz.getEnumConstants()).filter(x -> x.toString().equals(stateId)).findFirst().orElseThrow();
        CCD ccd = clazz.getField(enumType.toString()).getAnnotation(CCD.class);
        return ccd != null && !Strings.isNullOrEmpty(ccd.label()) ? ccd.label() :
                enumType.toString();
    }

    @Override
  public AboutToStartOrSubmitResponse aboutToSubmit(CallbackRequest request) {
    return controller.aboutToSubmit(request);
  }

  @Override
  public boolean hasSubmittedCallbackForEvent(String caseType, String event) {
        return controller.hasSubmittedCallback(caseType, event);
    }
}
