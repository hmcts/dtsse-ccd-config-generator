package uk.gov.hmcts.ccd.sdk.impl;

import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.ccd.sdk.CallbackRequestContext;
import uk.gov.hmcts.ccd.sdk.CallbackResponse;
import uk.gov.hmcts.ccd.sdk.SubmittedCallbackResponse;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

@Slf4j
@RequiredArgsConstructor
@Service(value = "uk.gov.hmcts.ccd.sdk.impl.CallbackDispatchService")
@ConditionalOnProperty(prefix = "decentralisation", name = "legacy-json-service", havingValue = "true")
public class CallbackDispatchService {

  private static final String ABOUT_TO_SUBMIT = "aboutToSubmit";
  private static final String SUBMITTED = "submitted";

  private Map<CallbackBinding, BiFunction<CallbackRequest, String, CallbackResponse<?>>> dispatchMap;
  private Map<CallbackBinding, Integer> retryAttempts;
  private final DefinitionRegistry definitionRegistry;
  private final ListableBeanFactory beanFactory;


  @PostConstruct
  void initialiseHandlerMaps() {
    dispatchMap = createDispatchMap();
  }

  public DispatchResult<CallbackResponse<?>> dispatchToHandlersAboutToSubmit(CallbackRequest callbackRequest) {
    var dispatcher = dispatchMap.get(bindingFor(callbackRequest, ABOUT_TO_SUBMIT));

    if (dispatcher == null) {
      return DispatchResult.noHandlerFound();
    }

    var result = dispatcher.apply(callbackRequest, CallbackRequestContext.getAuthorizationToken().orElse(null));

    return DispatchResult.handled(result);
  }

  public DispatchResult<SubmittedCallbackResponse> dispatchToHandlersSubmitted(CallbackRequest callbackRequest) {
    var binding = bindingFor(callbackRequest, SUBMITTED);
    var dispatcher = dispatchMap.get(binding);

    if (dispatcher == null) {
      return DispatchResult.noHandlerFound();
    }

    int maxAttempts;
    if (retryAttempts.get(binding) == null) {
      maxAttempts = 1;
    } else {
      maxAttempts = retryAttempts.get(binding) > 1 ? 3 : 1;
    }
    int attempts = 0;
    Exception lastException = null;

    while (attempts < maxAttempts) {
      attempts++;
      try {
        return DispatchResult.handled((SubmittedCallbackResponse) dispatcher.apply(
          callbackRequest,
          CallbackRequestContext.getAuthorizationToken().orElse(null)));
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

  private Map<CallbackBinding, BiFunction<CallbackRequest, String, CallbackResponse<?>>> createDispatchMap() {
    var definitions = definitionRegistry.loadDefinitions();
    Map<CallbackBinding, String> callbackBindings = new HashMap<>();
    retryAttempts = new HashMap<>();

    definitions.forEach((caseTypeId, definition) -> {
      definition.getEvents().forEach(event -> {
        if (!StringUtils.isBlank(event.getCallBackURLAboutToSubmitEvent())) {
          var previous = callbackBindings.putIfAbsent(
              new CallbackBinding(caseTypeId, event.getId(), ABOUT_TO_SUBMIT),
              event.getCallBackURLAboutToSubmitEvent()
          );
          if (previous != null) {
            throw new IllegalStateException(
                "Multiple Bindings found for case type %s, event id %s and callback type %s".formatted(
                    caseTypeId,
                    event.getId(),
                    ABOUT_TO_SUBMIT
                )
            );
          }
        }
        if (!StringUtils.isBlank(event.getCallBackURLSubmittedEvent())) {
          var binding = new CallbackBinding(caseTypeId, event.getId(), SUBMITTED);
          var previous = callbackBindings.putIfAbsent(binding, event.getCallBackURLSubmittedEvent());
          if (event.getRetriesTimeoutURLSubmittedEvent() != null) {
            int maxRetries = event.getRetriesTimeoutURLSubmittedEvent().size() > 1 ? 3 : 1;
            retryAttempts.putIfAbsent(binding, maxRetries);
          }
          if (previous != null) {
            throw new IllegalStateException(
                "Multiple Bindings found for case type %s, event id %s and callback type %s".formatted(
                    caseTypeId,
                    event.getId(),
                    SUBMITTED
                )
            );
          }
        }
      });
    });

    Map<String, EndpointInvoker> invokersByPath = discoverControllerEndpointsByPath();
    Map<CallbackBinding, BiFunction<CallbackRequest, String, CallbackResponse<?>>> dispatchMap = new HashMap<>();

    callbackBindings.forEach((binding, callbackUrl) -> {
      String callbackPath = normalisePath(callbackUrl);
      EndpointInvoker invoker = invokersByPath.get(callbackPath);
      if (invoker == null) {
        throw new IllegalStateException(
            "No callback controller method found for caseType=%s eventId=%s callbackType=%s url=%s (normalised=%s)"
                .formatted(
                    binding.caseTypeId(),
                    binding.eventId(),
                    binding.callbackType(),
                    callbackUrl,
                    callbackPath
                )
        );
      }
      dispatchMap.put(binding, invoker::invoke);
    });

    return Map.copyOf(dispatchMap);
  }

  private Map<String, EndpointInvoker> discoverControllerEndpointsByPath() {
    Map<String, EndpointInvoker> endpointsByPath = new HashMap<>();

    for (Object controllerBean : findControllerBeans()) {
      Class<?> controllerClass = AopUtils.getTargetClass(controllerBean);
      List<String> classPaths = classMappingPaths(controllerClass);

      for (Method method : controllerClass.getMethods()) {
        PostMapping postMapping = AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class);
        if (postMapping == null) {
          continue;
        }

        if (!isSupportedCallbackMethod(method)) {
          throw new IllegalStateException(
            """
              Callback controller method %s is not supported.
              Method must conform to the following signature:
              CallbackResponse<?> callback(CallbackRequest request, String authToken)
              """.formatted(method.toGenericString())
          );
        }

        List<String> methodPaths = methodMappingPaths(postMapping);
        for (String classPath : classPaths) {
          for (String methodPath : methodPaths) {
            String fullPath = normalisePath(combinePaths(classPath, methodPath));
            EndpointInvoker invoker = new EndpointInvoker(controllerBean, method);
            EndpointInvoker existing = endpointsByPath.putIfAbsent(fullPath, invoker);
            if (existing != null && !existing.hasSameMethodAs(invoker)) {
              throw new IllegalStateException(
                  "Multiple controller methods resolved for callback path %s (%s, %s)".formatted(
                      fullPath,
                      existing.describe(),
                      invoker.describe()
                  )
              );
            }
          }
        }
      }
    }

    return Map.copyOf(endpointsByPath);
  }

  private List<Object> findControllerBeans() {
    Map<String, Object> controllerBeans = new LinkedHashMap<>();
    controllerBeans.putAll(beanFactory.getBeansWithAnnotation(RestController.class));
    controllerBeans.putAll(beanFactory.getBeansWithAnnotation(Controller.class));
    return List.copyOf(controllerBeans.values());
  }

  private List<String> classMappingPaths(Class<?> controllerClass) {
    RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(controllerClass, RequestMapping.class);
    return mappingPaths(requestMapping == null ? null : requestMapping.path(),
        requestMapping == null ? null : requestMapping.value());
  }

  private List<String> methodMappingPaths(PostMapping postMapping) {
    return mappingPaths(postMapping.path(), postMapping.value());
  }

  private List<String> mappingPaths(String[] paths, String[] values) {
    String[] safePaths = paths == null ? new String[0] : paths;
    String[] safeValues = values == null ? new String[0] : values;
    List<String> mappingPaths = Stream.concat(Arrays.stream(safePaths), Arrays.stream(safeValues))
        .filter(StringUtils::isNotBlank)
        .distinct()
        .toList();
    return mappingPaths.isEmpty() ? List.of("") : mappingPaths;
  }

  private boolean isSupportedCallbackMethod(Method method) {
    Class<?>[] parameterTypes = method.getParameterTypes();
    return parameterTypes.length == 2
        && CallbackRequest.class.isAssignableFrom(parameterTypes[0])
        && String.class.equals(parameterTypes[1])
        && CallbackResponse.class.isAssignableFrom(method.getReturnType());
  }

  private String combinePaths(String classPath, String methodPath) {
    if (StringUtils.isBlank(classPath)) {
      return methodPath;
    }
    if (StringUtils.isBlank(methodPath)) {
      return classPath;
    }

    boolean classEndsWithSlash = classPath.endsWith("/");
    boolean methodStartsWithSlash = methodPath.startsWith("/");
    if (classEndsWithSlash && methodStartsWithSlash) {
      return classPath + methodPath.substring(1);
    }
    if (!classEndsWithSlash && !methodStartsWithSlash) {
      return classPath + "/" + methodPath;
    }
    return classPath + methodPath;
  }

  private String normalisePath(String urlOrPath) {
    if (StringUtils.isBlank(urlOrPath)) {
      return "/";
    }

    String path = urlOrPath.trim();
    if (path.startsWith("${")) {
      int variableEnd = path.indexOf('}');
      path = variableEnd >= 0 ? path.substring(variableEnd + 1) : "";
    } else {
      int schemeIndex = path.indexOf("://");
      if (schemeIndex >= 0) {
        int firstPathSlash = path.indexOf('/', schemeIndex + 3);
        path = firstPathSlash >= 0 ? path.substring(firstPathSlash) : "";
      }
    }

    int queryIndex = path.indexOf('?');
    if (queryIndex >= 0) {
      path = path.substring(0, queryIndex);
    }

    int fragmentIndex = path.indexOf('#');
    if (fragmentIndex >= 0) {
      path = path.substring(0, fragmentIndex);
    }

    if (path.isBlank()) {
      return "/";
    }

    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    path = path.replaceAll("/{2,}", "/");

    return path.length() > 1 && path.endsWith("/")
        ? path.substring(0, path.length() - 1)
        : path;
  }

  private record EndpointInvoker(Object bean, Method method) {

    private CallbackResponse<?> invoke(CallbackRequest request, String authToken) {
      try {
        return (CallbackResponse<?>) method.invoke(bean, request, authToken);
      } catch (InvocationTargetException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        if (cause instanceof Error error) {
          throw error;
        }
        throw new IllegalStateException("Callback endpoint invocation failed: " + describe(), cause);
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException("Could not access callback endpoint method: " + describe(), ex);
      }
    }

    private boolean hasSameMethodAs(EndpointInvoker other) {
      return method.equals(other.method)
          && method.getDeclaringClass().equals(other.method.getDeclaringClass());
    }

    private String describe() {
      return method.toGenericString();
    }
  }

  private CallbackBinding bindingFor(CallbackRequest callbackRequest, String callbackType) {
    return new CallbackBinding(
      callbackRequest.getCaseDetails().getCaseTypeId(),
      callbackRequest.getEventId(),
      callbackType
    );
  }

  private record CallbackBinding(String caseTypeId, String eventId, String callbackType) {
    private CallbackBinding {
      Objects.requireNonNull(caseTypeId, "caseTypeId must not be null");
      Objects.requireNonNull(eventId, "eventId must not be null");
      Objects.requireNonNull(callbackType, "callbackType must not be null");
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
