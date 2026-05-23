package uk.gov.hmcts.ccd.sdk.impl.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
class JsonCallbackRouteRegistry {

  private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";

  private final ApplicationContext applicationContext;
  private final ObjectMapper mapper;
  private final ObjectMapper requestMapper;
  private final Map<String, List<HandlerMethod>> routes;

  JsonCallbackRouteRegistry(ApplicationContext applicationContext,
                            ObjectMapper mapper,
                            @Qualifier("requestMappingHandlerMapping")
                            RequestMappingHandlerMapping handlerMapping) {
    this.applicationContext = applicationContext;
    this.mapper = mapper;
    this.requestMapper = mapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    this.routes = indexPostRoutes(handlerMapping);
  }

  Object invoke(String callbackUrl,
                Map<String, Object> payload,
                String authorisation,
                String serviceAuthorisation) {
    List<HandlerMethod> handlers = routes.get(normalise(callbackUrl));
    if (handlers == null || handlers.isEmpty()) {
      throw new IllegalStateException("No local Spring POST handler found for JSON callback " + callbackUrl);
    }
    if (handlers.size() > 1) {
      throw new IllegalStateException("Multiple local Spring POST handlers found for JSON callback " + callbackUrl);
    }

    HandlerMethod handler = handlers.getFirst();
    Object bean = resolveBean(handler);
    Method method = handler.getMethod();
    HttpHeaders headers = callbackHeaders(authorisation, serviceAuthorisation);
    Object[] args = resolveArguments(method, payload, headers);
    try {
      Object result = method.invoke(bean, args);
      if (result instanceof ResponseEntity<?> response) {
        assertSuccessfulResponse(callbackUrl, response.getStatusCode());
        return response.getBody();
      }
      return result;
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to invoke JSON callback " + callbackUrl, e);
    } catch (InvocationTargetException e) {
      Throwable target = e.getTargetException();
      if (target instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (target instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("JSON callback failed " + callbackUrl, target);
    }
  }

  private Map<String, List<HandlerMethod>> indexPostRoutes(RequestMappingHandlerMapping handlerMapping) {
    Map<String, List<HandlerMethod>> indexed = new LinkedHashMap<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
      RequestMappingInfo info = entry.getKey();
      if (!info.getMethodsCondition().getMethods().isEmpty()
          && !info.getMethodsCondition().getMethods().contains(RequestMethod.POST)) {
        continue;
      }

      for (String pattern : patterns(info)) {
        String path = normalise(pattern);
        indexed.computeIfAbsent(path, ignored -> new ArrayList<>()).add(entry.getValue());
      }
    }
    return Map.copyOf(indexed);
  }

  private List<String> patterns(RequestMappingInfo info) {
    if (info.getPathPatternsCondition() != null) {
      return new ArrayList<>(info.getPathPatternsCondition().getPatternValues());
    }
    if (info.getPatternsCondition() != null) {
      return new ArrayList<>(info.getPatternsCondition().getPatterns());
    }
    return List.of();
  }

  private Object resolveBean(HandlerMethod handler) {
    Object bean = handler.getBean();
    return bean instanceof String beanName ? applicationContext.getBean(beanName) : bean;
  }

  @SneakyThrows
  private Object[] resolveArguments(Method method, Map<String, Object> payload, HttpHeaders headers) {
    Object[] args = new Object[method.getParameterCount()];
    for (int i = 0; i < args.length; i++) {
      MethodParameter parameter = new MethodParameter(method, i);
      RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
      if (requestBody != null) {
        byte[] jsonPayload = requestMapper.writeValueAsBytes(payload);
        args[i] = requestMapper.readValue(jsonPayload, parameter.getParameterType());
        continue;
      }

      RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
      if (requestHeader != null) {
        args[i] = headerArgument(parameter.getParameterType(), requestHeader, headers);
        continue;
      }

      throw new IllegalStateException(
          "Unsupported JSON callback parameter %s on %s".formatted(parameter.getParameter(), method));
    }
    return args;
  }

  private Object headerArgument(Class<?> parameterType, RequestHeader requestHeader, HttpHeaders headers) {
    if (HttpHeaders.class.isAssignableFrom(parameterType)) {
      return headers;
    }
    if (String.class.equals(parameterType)) {
      String headerName = headerName(requestHeader);
      if (isSupportedHeader(headerName)) {
        return headers.getFirst(headerName);
      }
      throw new IllegalStateException("Unsupported JSON callback header " + headerName);
    }
    throw new IllegalStateException("Unsupported JSON callback header parameter type " + parameterType.getName());
  }

  private HttpHeaders callbackHeaders(String authorisation, String serviceAuthorisation) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, authorisation);
    headers.set(SERVICE_AUTHORIZATION, serviceAuthorisation);
    return headers;
  }

  private void assertSuccessfulResponse(String callbackUrl, HttpStatusCode statusCode) {
    if (!statusCode.is2xxSuccessful()) {
      throw new IllegalStateException(
          "JSON callback %s returned non-success status %s".formatted(callbackUrl, statusCode.value()));
    }
  }

  private boolean isSupportedHeader(String name) {
    return "authorization".equalsIgnoreCase(name) || "serviceauthorization".equalsIgnoreCase(name);
  }

  private String headerName(RequestHeader requestHeader) {
    String name = !requestHeader.name().isBlank() ? requestHeader.name() : requestHeader.value();
    if (name.isBlank()) {
      throw new IllegalStateException("JSON callback String @RequestHeader must declare a header name");
    }
    return name;
  }

  private String normalise(String callbackUrl) {
    String path = callbackUrl;
    if (callbackUrl.startsWith("http://") || callbackUrl.startsWith("https://")) {
      path = URI.create(callbackUrl).getPath();
    }
    if (path.length() > 1 && path.endsWith("/")) {
      return path.substring(0, path.length() - 1);
    }
    return path;
  }
}
