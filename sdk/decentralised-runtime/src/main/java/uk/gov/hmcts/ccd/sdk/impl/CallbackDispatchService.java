package uk.gov.hmcts.ccd.sdk.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.ccd.sdk.CallbackHandlerBean;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.Retries;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service(value = "uk.gov.hmcts.ccd.sdk.impl.CallbackDispatchService")
public class CallbackDispatchService {

  private static final String ABOUT_TO_SUBMIT = "aboutToSubmit";
  private static final String SUBMITTED = "submitted";
  private static final int DEFAULT_SUBMITTED_RETRIES = 1;

  private final List<CallbackHandlerBean> callbackHandlers;
  private volatile Map<Class<?>, Integer> submittedRetriesByHandlerClass = Map.of();

  @PostConstruct
  synchronized void initialiseSubmittedRetriesByHandlerClass() {
    if (!submittedRetriesByHandlerClass.isEmpty()) {
      return;
    }

    Map<Class<?>, Integer> retriesByHandlerClass = new HashMap<>();
    for (CallbackHandlerBean callbackHandler : callbackHandlers) {
      Class<?> callbackHandlerClass = callbackHandler.getClass();
      if (retriesByHandlerClass.containsKey(callbackHandlerClass)) {
        continue;
      }

      validateRetriesAnnotations(callbackHandlerClass);
      Method submittedMethod = getSubmittedMethod(callbackHandlerClass);
      Retries retriesAnnotation = submittedMethod.getAnnotation(Retries.class);
      int retries = retriesAnnotation == null ? DEFAULT_SUBMITTED_RETRIES : retriesAnnotation.value();
      retriesByHandlerClass.put(callbackHandlerClass, retries);
    }

    submittedRetriesByHandlerClass = Map.copyOf(retriesByHandlerClass);
  }

  public CallbackResponse dispatchToHandlersAboutToSubmit(CallbackRequest callbackRequest) {
    CallbackResponse response = null;
    boolean handled = false;

    for (CallbackHandlerBean callbackHandler : callbackHandlers) {
      if (callbackRequest.getEventId().equals(callbackHandler.getHandledEventId())) {
        if (!hasOverriddenMethod(callbackHandler, CallbackHandlerBean.class, ABOUT_TO_SUBMIT, CallbackRequest.class)) {
          log.warn("No implementation of aboutToSubmit callback found in {}", callbackHandler.getClass().getName());
          break;
        }
        response = callbackHandler.aboutToSubmit(callbackRequest);
        handled = true;
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

    for (CallbackHandlerBean callbackHandler : callbackHandlers) {
      if (callbackRequest.getEventId().equals(callbackHandler.getHandledEventId())) {
        if (!hasOverriddenMethod(callbackHandler, CallbackHandlerBean.class, SUBMITTED, CallbackRequest.class)) {
          log.warn("No implementation of submitted callback found in {}", callbackHandler.getClass().getName());
          break;
        }
        response = callbackHandler.submitted(callbackRequest);
        handled = true;
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

  public int resolveSubmittedRetries(String eventId) {
    ensureSubmittedRetriesInitialised();

    int retries = DEFAULT_SUBMITTED_RETRIES;
    for (CallbackHandlerBean callbackHandler : callbackHandlers) {
      if (!eventId.equals(callbackHandler.getHandledEventId())) {
        continue;
      }
      int handlerRetries = submittedRetriesByHandlerClass.getOrDefault(
          callbackHandler.getClass(),
          DEFAULT_SUBMITTED_RETRIES
      );
      retries = Math.max(retries, handlerRetries);
    }
    return retries;
  }

  private void ensureSubmittedRetriesInitialised() {
    if (!callbackHandlers.isEmpty() && submittedRetriesByHandlerClass.isEmpty()) {
      initialiseSubmittedRetriesByHandlerClass();
    }
  }

  private Method getSubmittedMethod(Class<?> callbackHandlerClass) {
    try {
      return callbackHandlerClass.getMethod(SUBMITTED, CallbackRequest.class);
    } catch (NoSuchMethodException ex) {
      throw new IllegalStateException(
          "No submitted callback method found on " + callbackHandlerClass.getName(), ex);
    }
  }

  private void validateRetriesAnnotations(Class<?> callbackHandlerClass) {
    for (Method method : callbackHandlerClass.getMethods()) {
      Retries retriesAnnotation = method.getAnnotation(Retries.class);
      if (retriesAnnotation == null) {
        continue;
      }

      boolean validSubmittedMethod = SUBMITTED.equals(method.getName())
          && method.getParameterCount() == 1
          && method.getParameterTypes()[0].equals(CallbackRequest.class)
          && SubmittedCallbackResponse.class.isAssignableFrom(method.getReturnType())
          && !method.getDeclaringClass().equals(CallbackHandlerBean.class);

      if (!validSubmittedMethod) {
        throw new IllegalStateException(
            "@Retries is only supported on CallbackHandlerBean#submitted implementations: "
            + callbackHandlerClass.getName() + "#" + method.getName());
      }

      if (retriesAnnotation.value() < 1) {
        throw new IllegalArgumentException("@Retries value must be greater than 0 on "
            + callbackHandlerClass.getName() + "#" + method.getName());
      }
    }
  }

  private boolean hasOverriddenMethod(Object object,
                                      Class<?> base,
                                      String methodName,
                                      Class<?>... parameterTypes) {
    try {
      Method method = object.getClass().getMethod(methodName, parameterTypes);
      // If the declaring class is the interface itself, it's using the default
      return !method.getDeclaringClass().equals(base);
    } catch (NoSuchMethodException ex) {
      return false;
    }
  }
}
