package uk.gov.hmcts.ccd.sdk.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.sdk.CallbackHandler;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Service(value = "uk.gov.hmcts.ccd.sdk.impl.CallbackDispatchService")
public class CallbackDispatchService {

  private final List<CallbackHandler<?>> callbackHandlers;

  public CallbackResponse<?> dispatchToHandlersAboutToSubmit(CallbackRequest callbackRequest) {
    CallbackResponse<?> response = null;
    boolean handled = false;

    for (var callbackHandler : callbackHandlers) {
      if (callbackRequest.getEventId().equals(callbackHandler.getHandledEventId())) {
        if (callbackHandler.acceptsAboutToSubmit()) {
          response = callbackHandler.aboutToSubmit(callbackRequest);
          handled = true;
        }
      }
    }

    if (!handled) {
      log.warn("No handler for case id {} aboutToSubmit callback case type {} event {}",
          callbackRequest.getCaseDetails().getId(),
          callbackRequest.getCaseDetails().getCaseTypeId(),
          callbackRequest.getEventId());
    }

    return response;
  }

  public SubmittedCallbackResponse dispatchToHandlersSubmitted(CallbackRequest callbackRequest) {
    SubmittedCallbackResponse response = null;
    boolean handled = false;

    for (CallbackHandler<?> callbackHandler : callbackHandlers) {
      if (callbackRequest.getEventId().equals(callbackHandler.getHandledEventId())) {
        if (callbackHandler.acceptsSubmitted()) {
          var maxAttempts = callbackHandler.getSubmittedRetries() + 1;
          int attempts = 0;

          while (attempts < maxAttempts) {
            attempts++;
            try {
              response = callbackHandler.submitted(callbackRequest);
              handled = true;
              break;
            } catch (Exception e) {
              log.error("Exception {} thrown in submitted callback for case {}, current attempt {}, {} retries remaining",
                e.getMessage(),
                callbackRequest.getCaseDetails().getId(),
                attempts,
                maxAttempts - attempts);
            }
          }
        }
      }
    }

    if (!handled) {
      log.warn("No handler for case id {} submitted callback case type {} event {}",
          callbackRequest.getCaseDetails().getId(),
          callbackRequest.getCaseDetails().getCaseTypeId(),
          callbackRequest.getEventId());
    }

    return response;
  }
}
