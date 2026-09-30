package uk.gov.hmcts.ccd.sdk.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

  public static final String CLIENT_CONTEXT_HEADER = "Client-Context";

  private final CcdCallbackExecutor executor;

  @Autowired
  public CallbackController(CcdCallbackExecutor executor) {
    this.executor = executor;
  }

  /**
   * Starts an event. CCD passes on the frontend's Client-Context header, which tells an external
   * event's start what the case cannot.
   */
  @PostMapping("/about-to-start")
  public AboutToStartOrSubmitResponse aboutToStart(
      @RequestBody CallbackRequest request,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorisation,
      @RequestHeader(value = CLIENT_CONTEXT_HEADER, required = false) String clientContext) {
    return executor.aboutToStart(request, authorisation, clientContext);
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
