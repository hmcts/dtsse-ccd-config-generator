package uk.gov.hmcts.ccd.sdk.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@RestController
@RequestMapping("/callbacks")
public class CallbackController {

  private final CcdCallbackExecutor executor;

  @Autowired
  public CallbackController(CcdCallbackExecutor executor) {
    this.executor = executor;
  }

  @PostMapping("/about-to-start")
  public AboutToStartOrSubmitResponse aboutToStart(@RequestBody CallbackRequest request) {
    return executor.aboutToStart(request);
  }

  @PostMapping("/about-to-submit")
  public AboutToStartOrSubmitResponse aboutToSubmit(@RequestBody CallbackRequest request) {
    return executor.aboutToSubmit(request);
  }

  @PostMapping("/submitted")
  public SubmittedCallbackResponse submitted(@RequestBody CallbackRequest request) {
    return executor.submitted(request);
  }

  @PostMapping("/mid-event")
  public AboutToStartOrSubmitResponse midEvent(@RequestBody CallbackRequest request,
                                               @RequestParam(name = "page") String page) {
    return executor.midEvent(request, page);
  }
}
