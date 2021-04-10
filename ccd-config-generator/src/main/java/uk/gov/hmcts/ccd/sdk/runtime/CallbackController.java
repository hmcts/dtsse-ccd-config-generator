package uk.gov.hmcts.ccd.sdk.runtime;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.AboutToStartOrSubmitCallbackResponse;
import uk.gov.hmcts.ccd.sdk.api.CallbackHandler;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

@RestController
@RequestMapping("/callbacks")
public class CallbackController {

  @Autowired
  private ResolvedCCDConfig<?, ?, ?> ccdConfig;

  @SneakyThrows
  @PostMapping("/about-to-start")
  public AboutToStartOrSubmitCallbackResponse handleAboutToStart(@RequestBody CallbackRequest request) {
    CallbackHandler<?, ?> handler = ccdConfig.aboutToSubmitCallbacks.get(request.getEventId());
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(request.getCaseDetails());
    JavaType t = mapper.getTypeFactory()
        .constructParametricType(CaseDetails.class, ccdConfig.typeArg, ccdConfig.stateArg);
    CaseDetails val = mapper.readValue(json, t);
    return handler.handle(val);
  }

}
