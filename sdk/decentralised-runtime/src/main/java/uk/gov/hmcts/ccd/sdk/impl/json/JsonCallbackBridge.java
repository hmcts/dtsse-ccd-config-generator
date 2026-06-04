package uk.gov.hmcts.ccd.sdk.impl.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.ccd.sdk.api.CaseDetails;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToStartOrSubmitResponse;
import uk.gov.hmcts.ccd.sdk.api.callback.AboutToSubmit;
import uk.gov.hmcts.ccd.sdk.api.callback.Submitted;
import uk.gov.hmcts.reform.ccd.client.model.SubmittedCallbackResponse;

@Component
public class JsonCallbackBridge {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";
  private static final String LOCAL_CALLBACK_BASE_URL = "decentralisation.local-callback-base-url";

  private final ApplicationContext applicationContext;
  private final ObjectMapper mapper;
  private final ObjectMapper requestMapper;
  private final Environment environment;
  private final HttpClient httpClient;
  private final Map<String, List<HandlerMethod>> routes;
  private final String localCallbackBaseUrl;

  JsonCallbackBridge(ApplicationContext applicationContext,
                     ObjectMapper mapper,
                     @Qualifier("requestMappingHandlerMapping")
                     RequestMappingHandlerMapping handlerMapping,
                     Environment environment) {
    this.applicationContext = applicationContext;
    this.mapper = mapper;
    this.requestMapper = mapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    this.environment = environment;
    this.httpClient = HttpClient.newHttpClient();
    this.localCallbackBaseUrl = localCallbackBaseUrl(environment);
    this.routes = indexPostRoutes(handlerMapping);
  }

  public void validate(String callbackUrl) {
    if (shouldInvokeLocally(callbackUrl)) {
      localHandler(callbackUrl);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public AboutToSubmit aboutToSubmit(String callbackUrl, String eventId) {
    return (details, detailsBefore) -> response(callbackUrl, eventId, details, detailsBefore);
  }

  @SuppressWarnings("rawtypes")
  public Submitted submitted(String callbackUrl, String eventId) {
    return (details, detailsBefore) -> {
      Object response = invoke(callbackUrl, eventId, details, detailsBefore);
      if (response == null) {
        return SubmittedCallbackResponse.builder().build();
      }
      Map<String, Object> callbackResponse = responseMap(response);
      return SubmittedCallbackResponse.builder()
          .confirmationHeader((String) callbackResponse.get("confirmation_header"))
          .confirmationBody((String) callbackResponse.get("confirmation_body"))
          .build();
    };
  }

  @SuppressWarnings("unchecked")
  private AboutToStartOrSubmitResponse response(String callbackUrl,
                                                String eventId,
                                                CaseDetails<?, ?> details,
                                                CaseDetails<?, ?> detailsBefore) {
    Object response = invoke(callbackUrl, eventId, details, detailsBefore);
    if (response == null) {
      throw new IllegalStateException("JSON callback " + callbackUrl + " returned no response");
    }
    Map<String, Object> callbackResponse = responseMap(response);
    Object data = callbackResponse.get("data");
    Object convertedData = data == null
        ? details.getData()
        : mapper.convertValue(data, dataClass(details));
    List<String> errors = (List<String>) callbackResponse.getOrDefault("errors", List.of());
    List<String> warnings = (List<String>) callbackResponse.getOrDefault("warnings", List.of());
    return AboutToStartOrSubmitResponse.builder()
        .data(convertedData)
        .errors(errors)
        .warnings(warnings)
        .state(callbackResponse.get("state"))
        .dataClassification((Map<String, Object>) callbackResponse.get("data_classification"))
        .securityClassification((String) callbackResponse.get("security_classification"))
        .errorMessageOverride((String) callbackResponse.get("error_message_override"))
        .build();
  }

  private Object invoke(String callbackUrl,
                        String eventId,
                        CaseDetails<?, ?> details,
                        CaseDetails<?, ?> detailsBefore) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("case_details", toCcdCaseDetails(details));
    payload.put("case_details_before", detailsBefore == null ? null : toCcdCaseDetails(detailsBefore));
    payload.put("event_id", eventId);

    return invoke(callbackUrl, payload);
  }

  Object invoke(String callbackUrl,
                Map<String, Object> payload) {
    if (!shouldInvokeLocally(callbackUrl)) {
      return invokeExternal(callbackUrl, payload);
    }

    HandlerMethod handler = localHandler(callbackUrl);
    Object bean = resolveBean(handler);
    Method method = handler.getMethod();
    HttpHeaders headers = callbackHeaders();
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

  private HandlerMethod localHandler(String callbackUrl) {
    List<HandlerMethod> handlers = routes.get(normalise(callbackUrl));
    if (handlers == null || handlers.isEmpty()) {
      throw new IllegalStateException("No local Spring POST handler found for JSON callback " + callbackUrl);
    }
    if (handlers.size() > 1) {
      throw new IllegalStateException("Multiple local Spring POST handlers found for JSON callback " + callbackUrl);
    }

    HandlerMethod handler = handlers.getFirst();
    validateSupported(handler);
    return handler;
  }

  private void validateSupported(HandlerMethod handler) {
    Method method = handler.getMethod();
    for (int i = 0; i < method.getParameterCount(); i++) {
      MethodParameter parameter = new MethodParameter(method, i);
      if (parameter.hasParameterAnnotation(RequestBody.class)) {
        continue;
      }

      RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
      if (requestHeader != null) {
        validateHeader(parameter.getParameterType(), requestHeader);
        continue;
      }

      throw new IllegalStateException(
          "Unsupported JSON callback parameter %s on %s".formatted(parameter.getParameter(), method));
    }
  }

  private void validateHeader(Class<?> parameterType, RequestHeader requestHeader) {
    if (HttpHeaders.class.isAssignableFrom(parameterType)) {
      return;
    }
    if (String.class.equals(parameterType) && isSupportedHeader(headerName(requestHeader))) {
      return;
    }
    throw new IllegalStateException("Unsupported JSON callback header parameter type " + parameterType.getName());
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

  private HttpHeaders callbackHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, currentRequestHeader(HttpHeaders.AUTHORIZATION));
    headers.set(SERVICE_AUTHORIZATION, currentRequestHeader(SERVICE_AUTHORIZATION));
    return headers;
  }

  private String currentRequestHeader(String name) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      String value = request.getHeader(name);
      return value == null ? "" : value;
    }
    return "";
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

  private Object invokeExternal(String callbackUrl, Map<String, Object> payload) {
    URI uri = externalUri(callbackUrl)
        .orElseThrow(() -> new IllegalStateException("No resolvable external URL found for JSON callback "
            + callbackUrl));
    HttpHeaders headers = callbackHeaders();
    try {
      HttpResponse<String> response = httpClient.send(
          externalRequest(uri, payload, headers),
          HttpResponse.BodyHandlers.ofString()
      );
      assertSuccessfulResponse(callbackUrl, HttpStatusCode.valueOf(response.statusCode()));
      String body = response.body();
      return body == null || body.isBlank() ? null : mapper.readValue(body, Object.class);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to invoke external JSON callback " + callbackUrl, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted invoking external JSON callback " + callbackUrl, e);
    }
  }

  private HttpRequest externalRequest(URI uri, Map<String, Object> payload, HttpHeaders headers) throws IOException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofByteArray(requestMapper.writeValueAsBytes(payload)));
    copyHeader(request, headers, HttpHeaders.AUTHORIZATION);
    copyHeader(request, headers, SERVICE_AUTHORIZATION);
    return request.build();
  }

  private void copyHeader(HttpRequest.Builder request, HttpHeaders headers, String name) {
    String value = headers.getFirst(name);
    if (value != null && !value.isBlank()) {
      request.header(name, value);
    }
  }

  private Optional<URI> externalUri(String callbackUrl) {
    Optional<URI> absoluteUri = absoluteUri(callbackUrl);
    if (absoluteUri.isPresent()) {
      return absoluteUri;
    }
    Placeholder placeholder = placeholder(callbackUrl);
    if (placeholder == null) {
      return Optional.empty();
    }
    String baseUrl = environment.getProperty(placeholder.name());
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = System.getenv(placeholder.name());
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      return Optional.empty();
    }
    return absoluteUri(joinUrl(baseUrl.trim(), placeholder.path()));
  }

  private String joinUrl(String baseUrl, String path) {
    if (path == null || path.isBlank()) {
      return baseUrl;
    }
    if (baseUrl.endsWith("/") && path.startsWith("/")) {
      return baseUrl + path.substring(1);
    }
    if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
      return baseUrl + "/" + path;
    }
    return baseUrl + path;
  }

  private String normalise(String callbackUrl) {
    String path = callbackUrl == null ? "" : callbackUrl.trim();
    Optional<URI> absoluteUri = absoluteUri(path);
    if (absoluteUri.isPresent()) {
      path = absoluteUri.get().getPath();
    }

    if (path.isBlank()) {
      return "/";
    }
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    path = path.replaceAll("/{2,}", "/");

    if (path.length() > 1 && path.endsWith("/")) {
      return path.substring(0, path.length() - 1);
    }
    return path;
  }

  private Placeholder placeholder(String callbackUrl) {
    if (!callbackUrl.startsWith("${")) {
      return null;
    }
    int placeholderEnd = callbackUrl.indexOf('}');
    if (placeholderEnd <= 2) {
      return null;
    }
    return new Placeholder(callbackUrl.substring(2, placeholderEnd), callbackUrl.substring(placeholderEnd + 1));
  }

  private boolean shouldInvokeLocally(String callbackUrl) {
    if (callbackUrl == null || callbackUrl.isBlank()) {
      return false;
    }

    return !localCallbackBaseUrl.isBlank() && callbackUrl.trim().startsWith(localCallbackBaseUrl);
  }

  private String localCallbackBaseUrl(Environment environment) {
    return Optional.ofNullable(environment.getProperty(LOCAL_CALLBACK_BASE_URL))
        .map(String::trim)
        .orElse("");
  }

  private Optional<URI> absoluteUri(String value) {
    try {
      URI uri = new URI(value);
      return uri.isAbsolute() ? Optional.of(uri) : Optional.empty();
    } catch (URISyntaxException | NullPointerException e) {
      return Optional.empty();
    }
  }

  private Map<String, Object> toCcdCaseDetails(CaseDetails<?, ?> details) {
    JsonNode node = mapper.valueToTree(details);
    Map<String, Object> callbackDetails = mapper.convertValue(
        node,
        mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
    );
    Object caseData = details.getData() == null ? Map.of() : mapper.convertValue(details.getData(), Object.class);
    callbackDetails.put("data", caseData);
    callbackDetails.put("case_data", caseData);
    return callbackDetails;
  }

  private Class<?> dataClass(CaseDetails<?, ?> details) {
    return details.getData() == null ? Map.class : details.getData().getClass();
  }

  private Map<String, Object> responseMap(Object response) {
    return response == null ? Map.of() : mapper.convertValue(response, MAP);
  }

  private record Placeholder(String name, String path) {
  }
}
