package uk.gov.hmcts.ccd.sdk.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.sdk.CallbackHandler;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service(value = "uk.gov.hmcts.ccd.sdk.impl.CallbackDispatchService")
public class CallbackDispatchService {

  private final List<CallbackHandler<?>> callbackHandlers;
  private Map<CallbackBinding, CallbackHandler<?>> aboutToSubmitHandlers;
  private Map<CallbackBinding, CallbackHandler<?>> submittedHandlers;

  @PostConstruct
  void initialiseHandlerMaps() {
    aboutToSubmitHandlers = createHandlerMap(CallbackHandler::acceptsAboutToSubmit, "aboutToSubmit");
    submittedHandlers = createHandlerMap(CallbackHandler::acceptsSubmitted, "submitted");
  }

  public DispatchResult<CallbackResponse<?>> dispatchToHandlersAboutToSubmit(CallbackRequest callbackRequest) {
    ensureInitialised();
    var handler = aboutToSubmitHandlers.get(bindingFor(callbackRequest));
    if (handler == null) {
      return DispatchResult.noHandlerFound();
    }

    return DispatchResult.handled(handler.aboutToSubmit(callbackRequest));
  }

  public DispatchResult<SubmittedCallbackResponse> dispatchToHandlersSubmitted(CallbackRequest callbackRequest) {
    ensureInitialised();
    var handler = submittedHandlers.get(bindingFor(callbackRequest));
    if (handler == null) {
      return DispatchResult.noHandlerFound();
    }

    var maxAttempts = handler.getSubmittedRetries() + 1;
    int attempts = 0;
    Exception lastException = null;

    while (attempts < maxAttempts) {
      attempts++;
      try {
        return DispatchResult.handled(handler.submitted(callbackRequest));
      } catch (Exception e) {
        lastException = e;
        log.error(
            "Submitted callback failed for case {}, case type {}, event {}, attempt {}, {} retries remaining",
            callbackRequest.getCaseDetails().getId(),
            callbackRequest.getCaseDetails().getCaseTypeId(),
            callbackRequest.getEventId(),
            attempts,
            maxAttempts - attempts,
            e
        );
      }
    }

    throw new IllegalStateException(
        "Submitted callback failed after %s attempt(s) for caseType=%s eventId=%s"
            .formatted(maxAttempts, callbackRequest.getCaseDetails().getCaseTypeId(), callbackRequest.getEventId()),
        lastException
    );
  }

  private Map<CallbackBinding, CallbackHandler<?>> createHandlerMap(
      Predicate<CallbackHandler<?>> filter,
      String phase
  ) {
    return callbackHandlers.stream()
        .filter(filter)
        .peek(handler -> validateBinding(handler, phase))
        .collect(Collectors.toUnmodifiableMap(
            this::bindingFor,
            handler -> handler,
            (first, second) -> {
              throw new IllegalStateException(
                  "Duplicate %s callback binding for caseType=%s eventId=%s (%s, %s)".formatted(
                      phase,
                      first.getHandledCaseTypeId(),
                      first.getHandledEventId(),
                      first.getClass().getName(),
                      second.getClass().getName()
                  )
              );
            }
        ));
  }

  private void ensureInitialised() {
    if (aboutToSubmitHandlers == null || submittedHandlers == null) {
      initialiseHandlerMaps();
    }
  }

  private void validateBinding(CallbackHandler<?> handler, String phase) {
    if (isBlank(handler.getHandledCaseTypeId()) || isBlank(handler.getHandledEventId())) {
      throw new IllegalStateException(
          "Invalid %s callback binding: handler %s must define non-empty caseTypeId and eventId"
              .formatted(phase, handler.getClass().getName())
      );
    }
  }

  private CallbackBinding bindingFor(CallbackRequest callbackRequest) {
    return new CallbackBinding(
        callbackRequest.getCaseDetails().getCaseTypeId(),
        callbackRequest.getEventId()
    );
  }

  private CallbackBinding bindingFor(CallbackHandler<?> handler) {
    return new CallbackBinding(handler.getHandledCaseTypeId(), handler.getHandledEventId());
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record CallbackBinding(String caseTypeId, String eventId) {
    private CallbackBinding {
      Objects.requireNonNull(caseTypeId, "caseTypeId must not be null");
      Objects.requireNonNull(eventId, "eventId must not be null");
    }
  }

  public record DispatchResult<T>(boolean handled, T response) {

    static <T> DispatchResult<T> handled(T response) {
      return new DispatchResult<>(true, response);
    }

    static <T> DispatchResult<T> noHandlerFound() {
      return new DispatchResult<>(false, null);
    }
  }
}
