package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.ccd.sdk.api.Webhook;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Slf4j
@Component
@Order(100)
class SpringHandlerLegacyCallbackResolver implements LegacyCallbackResolver {

  private final DefinitionRegistry definitionRegistry;
  private final RequestMappingHandlerMapping handlerMapping;
  private final ObjectMapper mapper;
  private final ObjectMapper callbackBodyMapper;
  private final LegacyCallbackResponseAdapter responseAdapter;
  private final ObjectProvider<HttpServletRequest> currentRequest;

  private Map<LegacyCallbackBinding, SpringHandlerCallback> callbacks = Map.of();

  SpringHandlerLegacyCallbackResolver(DefinitionRegistry definitionRegistry,
                                      @Qualifier("requestMappingHandlerMapping")
                                      RequestMappingHandlerMapping handlerMapping,
                                      ObjectMapper mapper,
                                      LegacyCallbackResponseAdapter responseAdapter,
                                      ObjectProvider<HttpServletRequest> currentRequest) {
    this.definitionRegistry = definitionRegistry;
    this.handlerMapping = handlerMapping;
    this.mapper = mapper;
    this.callbackBodyMapper = mapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.responseAdapter = responseAdapter;
    this.currentRequest = currentRequest;
  }

  @PostConstruct
  void initialiseCallbacks() {
    Map<LegacyCallbackBinding, SpringHandlerCallback> resolvedCallbacks = new HashMap<>();

    definitionRegistry.loadDefinitions().forEach((caseTypeId, definition) ->
        definition.getEvents().forEach(event -> {
          bindCallback(
              resolvedCallbacks,
              new LegacyCallbackBinding(caseTypeId, event.getId(), Webhook.AboutToSubmit),
              event.getCallBackURLAboutToSubmitEvent(),
              1
          );
          bindCallback(
              resolvedCallbacks,
              new LegacyCallbackBinding(caseTypeId, event.getId(), Webhook.Submitted),
              event.getCallBackURLSubmittedEvent(),
              submittedAttempts(event.getRetriesTimeoutURLSubmittedEvent())
          );
        })
    );

    this.callbacks = Map.copyOf(resolvedCallbacks);
    log.info("Resolved {} JSON legacy callback handler bindings", callbacks.size());
  }

  @Override
  public Optional<LegacyCallback> resolve(String caseTypeId, String eventId) {
    var aboutToSubmit = callbacks.get(new LegacyCallbackBinding(
        caseTypeId,
        eventId,
        Webhook.AboutToSubmit
    ));
    var submitted = callbacks.get(new LegacyCallbackBinding(
        caseTypeId,
        eventId,
        Webhook.Submitted
    ));

    if (aboutToSubmit == null && submitted == null) {
      return Optional.empty();
    }
    return Optional.of(new SpringHandlerLegacyCallback(aboutToSubmit, submitted));
  }

  private void bindCallback(Map<LegacyCallbackBinding, SpringHandlerCallback> resolvedCallbacks,
                            LegacyCallbackBinding binding,
                            String callbackUrl,
                            int submittedAttempts) {
    if (callbackUrl == null || callbackUrl.isBlank()) {
      return;
    }

    String path = URI.create(callbackUrl).getPath();
    HandlerMethod handlerMethod = findPostHandler(path).orElseThrow(() -> new IllegalStateException(
        "No POST handler found for legacy callback caseType=%s eventId=%s callbackType=%s url=%s path=%s"
            .formatted(binding.caseTypeId(), binding.eventId(), binding.callbackType(), callbackUrl, path)));

    SpringHandlerCallback callback = new SpringHandlerCallback(
        handlerMethod.createWithResolvedBean(),
        submittedAttempts
    );
    SpringHandlerCallback previous = resolvedCallbacks.putIfAbsent(binding, callback);
    if (previous != null && !previous.describesSameHandler(callback)) {
      throw new IllegalStateException(
          "Multiple handlers found for legacy callback caseType=%s eventId=%s callbackType=%s"
              .formatted(binding.caseTypeId(), binding.eventId(), binding.callbackType()));
    }
  }

  private Optional<HandlerMethod> findPostHandler(String path) {
    List<HandlerMethod> matches = handlerMapping.getHandlerMethods().entrySet().stream()
        .filter(entry -> handlesPost(entry.getKey()))
        .filter(entry -> paths(entry.getKey()).stream()
            .anyMatch(path::equals))
        .map(Map.Entry::getValue)
        .toList();

    return matches.stream().reduce((left, right) -> {
      if (!sameHandler(left, right)) {
        throw new IllegalStateException(
            "Multiple POST handlers found for legacy callback path %s: %s and %s"
                .formatted(path, left.getMethod().toGenericString(), right.getMethod().toGenericString()));
      }
      return left;
    });
  }

  private boolean handlesPost(RequestMappingInfo mappingInfo) {
    Set<?> methods = mappingInfo.getMethodsCondition().getMethods();
    return methods.isEmpty() || methods.contains(org.springframework.web.bind.annotation.RequestMethod.POST);
  }

  private Set<String> paths(RequestMappingInfo mappingInfo) {
    Set<String> paths = new LinkedHashSet<>();
    if (mappingInfo.getPathPatternsCondition() != null) {
      paths.addAll(mappingInfo.getPathPatternsCondition().getPatternValues());
    }
    if (mappingInfo.getPatternsCondition() != null) {
      paths.addAll(mappingInfo.getPatternsCondition().getPatterns());
    }
    return paths;
  }

  private boolean sameHandler(HandlerMethod left, HandlerMethod right) {
    return left.getMethod().equals(right.getMethod()) && left.getBeanType().equals(right.getBeanType());
  }

  private int submittedAttempts(java.util.List<Integer> retries) {
    return retries == null || retries.isEmpty() ? 1 : 3;
  }

  private record LegacyCallbackBinding(
      String caseTypeId,
      String eventId,
      Webhook callbackType
  ) {
  }

  private class SpringHandlerLegacyCallback implements LegacyCallback {

    private final SpringHandlerCallback aboutToSubmit;
    private final SpringHandlerCallback submitted;

    private SpringHandlerLegacyCallback(SpringHandlerCallback aboutToSubmit, SpringHandlerCallback submitted) {
      this.aboutToSubmit = aboutToSubmit;
      this.submitted = submitted;
    }

    @Override
    public Optional<LegacyAboutToSubmitCallbackResponse> aboutToSubmit(CallbackRequest request) {
      if (aboutToSubmit == null) {
        return Optional.empty();
      }
      return Optional.of(responseAdapter.aboutToSubmit(aboutToSubmit.invoke(request)));
    }

    @Override
    public Optional<SubmittedCallbackResponse> submitted(CallbackRequest request) {
      if (submitted == null) {
        return Optional.empty();
      }
      return Optional.of(responseAdapter.submitted(submitted.invoke(request)));
    }

    @Override
    public int submittedAttempts() {
      return submitted == null ? 1 : submitted.submittedAttempts();
    }
  }

  private class SpringHandlerCallback {

    private final HandlerMethod handlerMethod;
    private final int submittedAttempts;

    private SpringHandlerCallback(HandlerMethod handlerMethod, int submittedAttempts) {
      this.handlerMethod = handlerMethod;
      this.submittedAttempts = submittedAttempts;
    }

    Object invoke(CallbackRequest request) {
      Method method = handlerMethod.getMethod();
      ReflectionUtils.makeAccessible(method);
      try {
        return method.invoke(handlerMethod.getBean(), arguments(request));
      } catch (InvocationTargetException ex) {
        Throwable target = ex.getTargetException();
        if (target instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        if (target instanceof Error error) {
          throw error;
        }
        throw new IllegalStateException("Legacy callback handler failed", target);
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException("Unable to invoke legacy callback handler", ex);
      }
    }

    boolean describesSameHandler(SpringHandlerCallback other) {
      return sameHandler(handlerMethod, other.handlerMethod);
    }

    int submittedAttempts() {
      return submittedAttempts;
    }

    private Object[] arguments(CallbackRequest request) {
      MethodParameter[] parameters = handlerMethod.getMethodParameters();
      Object[] arguments = new Object[parameters.length];
      for (int i = 0; i < parameters.length; i++) {
        arguments[i] = argument(parameters[i], request);
      }
      return arguments;
    }

    private Object argument(MethodParameter parameter, CallbackRequest request) {
      RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
      if (requestHeader != null) {
        return headerArgument(parameter.getParameterType(), headerName(requestHeader));
      }

      RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
      if (requestBody != null) {
        return bodyArgument(parameter.getParameterType(), request);
      }

      if (CallbackRequest.class.isAssignableFrom(parameter.getParameterType())) {
        return request;
      }

      return bodyArgument(parameter.getParameterType(), request);
    }

    private Object bodyArgument(Class<?> parameterType, CallbackRequest request) {
      if (CallbackRequest.class.isAssignableFrom(parameterType)) {
        return request;
      }
      return callbackBodyMapper.convertValue(request, parameterType);
    }

    private Object headerArgument(Class<?> parameterType, String headerName) {
      if (!"authorization".equalsIgnoreCase(headerName)) {
        return null;
      }
      String authorizationHeader = currentRequest.getObject().getHeader(HttpHeaders.AUTHORIZATION);
      if (String.class.equals(parameterType)) {
        return authorizationHeader;
      }
      if (HttpHeaders.class.isAssignableFrom(parameterType)) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        return headers;
      }
      return mapper.convertValue(authorizationHeader, parameterType);
    }

    private String headerName(RequestHeader requestHeader) {
      if (!requestHeader.name().isBlank()) {
        return requestHeader.name();
      }
      if (!requestHeader.value().isBlank()) {
        return requestHeader.value();
      }
      return "";
    }
  }
}
