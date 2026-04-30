package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.DispatcherServlet;
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
  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

  private Map<CallbackBinding, CallbackEndpoint> dispatchMap;
  private Map<CallbackBinding, Integer> retryAttempts;
  private final DefinitionRegistry definitionRegistry;
  private final DispatcherServlet dispatcherServlet;
  private final ObjectMapper objectMapper;
  @Value("${decentralisation.local-callback-base-urls:${ET_COS_URL:}}")
  private String localCallbackBaseUrls;

  @PostConstruct
  void initialiseHandlerMaps() {
    dispatchMap = createDispatchMap();
  }

  public DispatchResult<CallbackResponse<?>> dispatchToHandlersAboutToSubmit(CallbackRequest callbackRequest,
                                                                             String authorisation) {
    var endpoint = dispatchMap.get(bindingFor(callbackRequest, ABOUT_TO_SUBMIT));
    if (endpoint == null) {
      return DispatchResult.noHandlerFound();
    }

    return DispatchResult.handled(invoke(endpoint, callbackRequest, authorisation));
  }

  public DispatchResult<SubmittedCallbackResponse> dispatchToHandlersSubmitted(CallbackRequest callbackRequest,
                                                                               String authorisation) {
    var binding = bindingFor(callbackRequest, SUBMITTED);
    var endpoint = dispatchMap.get(binding);
    if (endpoint == null) {
      return DispatchResult.noHandlerFound();
    }

    int maxAttempts = retryAttempts.getOrDefault(binding, 1);
    int attempts = 0;
    Exception lastException = null;

    while (attempts < maxAttempts) {
      attempts++;
      try {
        return DispatchResult.handled(asSubmittedResponse(invoke(endpoint, callbackRequest, authorisation)));
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

  private Map<CallbackBinding, CallbackEndpoint> createDispatchMap() {
    var definitions = definitionRegistry.loadDefinitions();
    Map<CallbackBinding, CallbackEndpoint> callbacks = new HashMap<>();
    retryAttempts = new HashMap<>();

    definitions.forEach((caseTypeId, definition) -> definition.getEvents().forEach(event -> {
      addBinding(
          callbacks,
          new CallbackBinding(caseTypeId, event.getId(), ABOUT_TO_SUBMIT),
          event.getCallBackURLAboutToSubmitEvent()
      );

      var submittedBinding = new CallbackBinding(caseTypeId, event.getId(), SUBMITTED);
      addBinding(callbacks, submittedBinding, event.getCallBackURLSubmittedEvent());
      if (event.getRetriesTimeoutURLSubmittedEvent() != null) {
        retryAttempts.putIfAbsent(
            submittedBinding,
            event.getRetriesTimeoutURLSubmittedEvent().size() > 1 ? 3 : 1
        );
      }
    }));

    log.info("Initialised callback dispatch map with {} local callback bindings", callbacks.size());
    return Map.copyOf(callbacks);
  }

  private void addBinding(Map<CallbackBinding, CallbackEndpoint> callbacks,
                          CallbackBinding binding,
                          String callbackUrl) {
    if (StringUtils.isBlank(callbackUrl) || !shouldBindLocally(callbackUrl)) {
      return;
    }

    CallbackEndpoint previous = callbacks.putIfAbsent(binding, normaliseEndpoint(callbackUrl));
    if (previous != null) {
      throw new IllegalStateException(
          "Multiple Bindings found for case type %s, event id %s and callback type %s".formatted(
              binding.caseTypeId(),
              binding.eventId(),
              binding.callbackType()
          )
      );
    }
  }

  private CallbackResponse<?> invoke(CallbackEndpoint endpoint, CallbackRequest callbackRequest, String authorisation) {
    try {
      ServletRequestAttributes attributes =
          (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
      HttpServletRequest request = new CallbackServletRequest(
          attributes.getRequest(),
          endpoint.path(),
          endpoint.queryString(),
          objectMapper.writeValueAsBytes(callbackRequest),
          authorisation
      );
      HttpServletResponse originalResponse = attributes.getResponse();
      if (originalResponse == null) {
        throw new IllegalStateException("No current HTTP response available for callback dispatch");
      }
      CapturingHttpServletResponse response = new CapturingHttpServletResponse(originalResponse);

      dispatcherServlet.service(request, response);
      if (!response.isSuccessful()) {
        throw new IllegalStateException(
            "Callback endpoint %s returned non-success status %s: %s"
                .formatted(endpoint.path(), response.getStatus(), response.getContentAsString())
        );
      }

      byte[] content = response.getContentAsByteArray();
      return content.length == 0 ? null : objectMapper.readValue(content, NormalisedCallbackResponse.class);
    } catch (ServletException | IOException ex) {
      throw new IllegalStateException("Callback endpoint invocation failed for " + endpoint.path(), ex);
    }
  }

  private CallbackEndpoint normaliseEndpoint(String urlOrPath) {
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

    int fragmentIndex = path.indexOf('#');
    if (fragmentIndex >= 0) {
      path = path.substring(0, fragmentIndex);
    }

    String queryString = null;
    int queryIndex = path.indexOf('?');
    if (queryIndex >= 0) {
      queryString = path.substring(queryIndex + 1);
      path = path.substring(0, queryIndex);
    }

    if (path.isBlank()) {
      path = "/";
    }
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    path = path.replaceAll("/{2,}", "/");
    if (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }

    return new CallbackEndpoint(path, StringUtils.isBlank(queryString) ? null : queryString);
  }

  private boolean shouldBindLocally(String callbackUrl) {
    if (StringUtils.isBlank(localCallbackBaseUrls) || callbackUrl.startsWith("${")) {
      return true;
    }

    URI callbackUri = tryParseAbsoluteUri(callbackUrl);
    if (callbackUri == null) {
      return true;
    }

    return Arrays.stream(localCallbackBaseUrls.split(","))
        .map(String::trim)
        .filter(StringUtils::isNotBlank)
        .map(this::tryParseAbsoluteUri)
        .filter(Objects::nonNull)
        .anyMatch(localUri -> sameAuthority(callbackUri, localUri));
  }

  private URI tryParseAbsoluteUri(String value) {
    try {
      URI uri = new URI(value);
      return uri.isAbsolute() ? uri : null;
    } catch (URISyntaxException ex) {
      return null;
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

  private static SubmittedCallbackResponse asSubmittedResponse(CallbackResponse<?> response) {
    if (response == null) {
      return null;
    }
    if (response instanceof SubmittedCallbackResponse submittedCallbackResponse) {
      return submittedCallbackResponse;
    }
    return NormalisedCallbackResponse.from(response);
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

  private record CallbackEndpoint(String path, String queryString) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record NormalisedCallbackResponse(
      Object data,
      List<String> errors,
      List<String> warnings,
      String state,
      @JsonAlias("data_classification") Map<String, Object> dataClassification,
      @JsonAlias("security_classification") String securityClassification,
      @JsonAlias("error_message_override") String errorMessageOverride,
      @JsonAlias("confirmation_header") String confirmationHeader,
      @JsonAlias("confirmation_body") String confirmationBody
  ) implements CallbackResponse<Object>, SubmittedCallbackResponse {

    private static NormalisedCallbackResponse from(CallbackResponse<?> response) {
      return new NormalisedCallbackResponse(
          response.getData(),
          response.getErrors(),
          response.getWarnings(),
          response.getState(),
          response.getDataClassification(),
          response.getSecurityClassification(),
          response.getErrorMessageOverride(),
          null,
          null
      );
    }

    @Override
    public Object getData() {
      return data;
    }

    @Override
    public List<String> getErrors() {
      return errors;
    }

    @Override
    public List<String> getWarnings() {
      return warnings;
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public Map<String, Object> getDataClassification() {
      return dataClassification;
    }

    @Override
    public String getSecurityClassification() {
      return securityClassification;
    }

    @Override
    public String getErrorMessageOverride() {
      return errorMessageOverride;
    }

    @Override
    public String getConfirmationHeader() {
      return confirmationHeader;
    }

    @Override
    public String getConfirmationBody() {
      return confirmationBody;
    }
  }

  private static final class CallbackServletRequest extends HttpServletRequestWrapper {
    private final String path;
    private final String queryString;
    private final byte[] body;
    private final Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, String[]> parameters;
    private String characterEncoding = DEFAULT_CHARSET.name();

    private CallbackServletRequest(HttpServletRequest request,
                                   String path,
                                   String queryString,
                                   byte[] body,
                                   String authorisation) {
      super(request);
      this.path = path;
      this.queryString = queryString;
      this.body = body;
      this.parameters = parseParameters(queryString);
      if (authorisation != null) {
        headers.put(HttpHeaders.AUTHORIZATION, List.of(authorisation));
      }
      headers.put(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON_VALUE));
      headers.put(HttpHeaders.ACCEPT, List.of(MediaType.APPLICATION_JSON_VALUE));
    }

    @Override
    public String getMethod() {
      return "POST";
    }

    @Override
    public String getRequestURI() {
      return getContextPath() + path;
    }

    @Override
    public StringBuffer getRequestURL() {
      StringBuffer url = new StringBuffer();
      url.append(getScheme()).append("://").append(getServerName());
      if (getServerPort() > 0) {
        url.append(':').append(getServerPort());
      }
      return url.append(getRequestURI());
    }

    @Override
    public String getServletPath() {
      return path;
    }

    @Override
    public String getPathInfo() {
      return null;
    }

    @Override
    public String getQueryString() {
      return queryString;
    }

    @Override
    public Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
      return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
      if (value == null) {
        removeAttribute(name);
      } else {
        attributes.put(name, value);
      }
    }

    @Override
    public void removeAttribute(String name) {
      attributes.remove(name);
    }

    @Override
    public String getHeader(String name) {
      List<String> values = headers.get(name);
      return values == null || values.isEmpty() ? super.getHeader(name) : values.getFirst();
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      List<String> values = headers.get(name);
      return values == null ? super.getHeaders(name) : Collections.enumeration(values);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      Enumeration<String> originalHeaderNames = super.getHeaderNames();
      List<String> names = originalHeaderNames == null ? new ArrayList<>() : Collections.list(originalHeaderNames);
      names.addAll(headers.keySet());
      return Collections.enumeration(names.stream().distinct().toList());
    }

    @Override
    public String getContentType() {
      return MediaType.APPLICATION_JSON_VALUE;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new ByteArrayServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new java.io.StringReader(new String(body, Charset.forName(characterEncoding))));
    }

    @Override
    public String getCharacterEncoding() {
      return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String encoding) {
      this.characterEncoding = encoding == null ? DEFAULT_CHARSET.name() : encoding;
    }

    @Override
    public String getParameter(String name) {
      String[] values = parameters.get(name);
      return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      return parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
      return Collections.enumeration(parameters.keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
      return parameters.get(name);
    }

    private static Map<String, String[]> parseParameters(String queryString) {
      if (StringUtils.isBlank(queryString)) {
        return Map.of();
      }

      Map<String, List<String>> values = new LinkedHashMap<>();
      for (String pair : queryString.split("&")) {
        if (pair.isBlank()) {
          continue;
        }
        int separator = pair.indexOf('=');
        String name = separator < 0 ? pair : pair.substring(0, separator);
        String value = separator < 0 ? "" : pair.substring(separator + 1);
        values.computeIfAbsent(decode(name), ignored -> new ArrayList<>()).add(decode(value));
      }

      Map<String, String[]> parameters = new LinkedHashMap<>();
      values.forEach((key, parameterValues) -> parameters.put(key, parameterValues.toArray(String[]::new)));
      return Collections.unmodifiableMap(parameters);
    }

    private static String decode(String value) {
      return URLDecoder.decode(value, DEFAULT_CHARSET);
    }
  }

  private static final class ByteArrayServletInputStream extends ServletInputStream {
    private final ByteArrayInputStream inputStream;

    private ByteArrayServletInputStream(byte[] body) {
      this.inputStream = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return inputStream.read();
    }

    @Override
    public boolean isFinished() {
      return inputStream.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      throw new UnsupportedOperationException("Async callback request reads are not supported");
    }
  }

  private static final class CapturingHttpServletResponse extends HttpServletResponseWrapper {
    private final Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private int status = HttpServletResponse.SC_OK;
    private String characterEncoding = DEFAULT_CHARSET.name();
    private String contentType;
    private Locale locale = Locale.getDefault();
    private boolean committed;

    private CapturingHttpServletResponse(HttpServletResponse response) {
      super(response);
    }

    private boolean isSuccessful() {
      return status >= 200 && status < 300;
    }

    private byte[] getContentAsByteArray() throws IOException {
      flushBuffer();
      return body.toByteArray();
    }

    private String getContentAsString() throws IOException {
      return new String(getContentAsByteArray(), Charset.forName(characterEncoding));
    }

    @Override
    public void addCookie(Cookie cookie) {
      addHeader(HttpHeaders.SET_COOKIE, "%s=%s".formatted(cookie.getName(), cookie.getValue()));
    }

    @Override
    public boolean containsHeader(String name) {
      return headers.containsKey(name);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      setStatus(sc);
      resetBuffer();
      if (msg != null) {
        getWriter().write(msg);
      }
      committed = true;
    }

    @Override
    public void sendError(int sc) {
      setStatus(sc);
      committed = true;
    }

    @Override
    public void sendRedirect(String location) {
      setStatus(HttpServletResponse.SC_FOUND);
      setHeader(HttpHeaders.LOCATION, location);
      committed = true;
    }

    @Override
    public void setDateHeader(String name, long date) {
      setHeader(name, Long.toString(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
      addHeader(name, Long.toString(date));
    }

    @Override
    public void setHeader(String name, String value) {
      headers.put(name, new ArrayList<>(List.of(value)));
    }

    @Override
    public void addHeader(String name, String value) {
      headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
    }

    @Override
    public void setIntHeader(String name, int value) {
      setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
      addHeader(name, Integer.toString(value));
    }

    @Override
    public void setStatus(int sc) {
      this.status = sc;
    }

    @Override
    public int getStatus() {
      return status;
    }

    @Override
    public String getHeader(String name) {
      List<String> values = headers.get(name);
      return values == null || values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public Collection<String> getHeaders(String name) {
      return List.copyOf(headers.getOrDefault(name, List.of()));
    }

    @Override
    public Collection<String> getHeaderNames() {
      return List.copyOf(headers.keySet());
    }

    @Override
    public String getCharacterEncoding() {
      return characterEncoding;
    }

    @Override
    public String getContentType() {
      return contentType;
    }

    @Override
    public ServletOutputStream getOutputStream() {
      if (outputStream == null) {
        outputStream = new ServletOutputStream() {
          @Override
          public void write(int b) {
            body.write(b);
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener writeListener) {
            throw new UnsupportedOperationException("Async callback response writes are not supported");
          }
        };
      }
      return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws UnsupportedEncodingException {
      if (writer == null) {
        writer = new PrintWriter(new OutputStreamWriter(body, characterEncoding));
      }
      return writer;
    }

    @Override
    public void setCharacterEncoding(String charset) {
      this.characterEncoding = charset == null ? DEFAULT_CHARSET.name() : charset;
    }

    @Override
    public void setContentLength(int len) {
    }

    @Override
    public void setContentLengthLong(long len) {
    }

    @Override
    public void setContentType(String type) {
      this.contentType = type;
    }

    @Override
    public void setBufferSize(int size) {
    }

    @Override
    public int getBufferSize() {
      return body.size();
    }

    @Override
    public void flushBuffer() {
      if (writer != null) {
        writer.flush();
      }
      committed = true;
    }

    @Override
    public void resetBuffer() {
      body.reset();
    }

    @Override
    public boolean isCommitted() {
      return committed;
    }

    @Override
    public void reset() {
      resetBuffer();
      headers.clear();
      status = HttpServletResponse.SC_OK;
      contentType = null;
      committed = false;
    }

    @Override
    public void setLocale(Locale locale) {
      this.locale = locale;
    }

    @Override
    public Locale getLocale() {
      return locale;
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
