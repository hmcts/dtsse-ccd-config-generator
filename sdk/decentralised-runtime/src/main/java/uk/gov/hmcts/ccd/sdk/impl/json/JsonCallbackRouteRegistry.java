package uk.gov.hmcts.ccd.sdk.impl.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

@Component
class JsonCallbackRouteRegistry {

  private static final String SERVICE_AUTHORIZATION = "ServiceAuthorization";
  private static final String LOCAL_CALLBACK_BASE_URLS = "decentralisation.local-callback-base-urls";
  private static final Set<String> LOCAL_PLACEHOLDER_NAMES = Set.of(
      "CCD_DEF_BASE_URL",
      "CCD_DEF_CASE_SERVICE_BASE_URL",
      "ET_COS_URL"
  );

  private final ApplicationContext applicationContext;
  private final ObjectMapper mapper;
  private final ObjectMapper requestMapper;
  private final Environment environment;
  private final HttpClient httpClient;
  private final Map<String, List<HandlerMethod>> routes;
  private final String localCallbackBaseUrls;

  JsonCallbackRouteRegistry(ApplicationContext applicationContext,
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
    this.localCallbackBaseUrls = localCallbackBaseUrls(environment);
    this.routes = indexPostRoutes(handlerMapping);
  }

  void validate(String callbackUrl) {
    if (shouldInvokeLocally(callbackUrl)) {
      localHandler(callbackUrl);
    }
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
    } else {
      Placeholder placeholder = placeholder(path);
      if (placeholder != null) {
        path = placeholder.path();
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

    Optional<URI> callbackUri = externalUri(callbackUrl.trim());
    if (callbackUri.isPresent()) {
      return localBaseUris().stream().anyMatch(localUri -> sameAuthority(callbackUri.get(), localUri));
    }

    Placeholder placeholder = placeholder(callbackUrl.trim());
    if (placeholder != null) {
      return LOCAL_PLACEHOLDER_NAMES.contains(placeholder.name());
    }

    return absoluteUri(callbackUrl.trim()).isEmpty();
  }

  private List<URI> localBaseUris() {
    if (localCallbackBaseUrls == null || localCallbackBaseUrls.isBlank()) {
      return List.of();
    }
    return Arrays.stream(localCallbackBaseUrls.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(this::absoluteUri)
        .flatMap(Optional::stream)
        .toList();
  }

  private String localCallbackBaseUrls(Environment environment) {
    String configured = environment.getProperty(LOCAL_CALLBACK_BASE_URLS);
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    return Optional.ofNullable(environment.getProperty("ET_COS_URL"))
        .or(() -> Optional.ofNullable(System.getenv("ET_COS_URL")))
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

  private boolean sameAuthority(URI left, URI right) {
    return Objects.equals(left.getHost(), right.getHost())
        && effectivePort(left) == effectivePort(right);
  }

  private int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private record Placeholder(String name, String path) {
  }
}
