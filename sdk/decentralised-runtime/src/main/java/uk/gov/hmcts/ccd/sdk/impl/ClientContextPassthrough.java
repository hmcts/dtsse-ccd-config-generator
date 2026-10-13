package uk.gov.hmcts.ccd.sdk.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Carries the Client-Context header through a submission as CCD carries it through callbacks. Each callback is sent
 * the current context. A context a callback returns is merged into the one it was sent, replacing its top-level
 * fields, as CCD's {@code ClientContextUtil} does; the result is sent to later callbacks and goes back to CCD on the
 * submit response.
 *
 * <p>The callbacks run on the submission's request thread, so the request holds the merged context.
 */
public final class ClientContextPassthrough {

  public static final String CLIENT_CONTEXT = "Client-Context";

  private static final String RETURNED = ClientContextPassthrough.class.getName() + ".returned";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ClientContextPassthrough() {
  }

  /** The context after the latest callback that returned one, else the one the submission arrived with. */
  public static Optional<String> current() {
    return returned().or(() -> request()
        .map(request -> request.getHeader(CLIENT_CONTEXT))
        .filter(ClientContextPassthrough::present));
  }

  /** The context after the latest callback that returned one, if any callback in this submission did. */
  public static Optional<String> returned() {
    return request().map(request -> (String) request.getAttribute(RETURNED));
  }

  /**
   * Merges a context a callback returned into the current one. A callback that returns none, or one that is not a
   * base64-encoded JSON object, leaves it as it was.
   */
  public static void returned(String clientContext) {
    if (present(clientContext)) {
      request().ifPresent(request -> {
        String merged = merge(current().orElse(null), clientContext);
        if (merged != null) {
          request.setAttribute(RETURNED, merged);
        }
      });
    }
  }

  static String merge(String original, String returned) {
    var returnedNode = decode(returned);
    if (returnedNode == null) {
      return original;
    }
    var originalNode = decode(original);
    if (originalNode == null) {
      return unbracketed(returned);
    }
    originalNode.setAll(returnedNode);
    return Base64.getEncoder().encodeToString(originalNode.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static ObjectNode decode(String context) {
    if (context == null) {
      return null;
    }
    try {
      String json = new String(Base64.getDecoder().decode(unbracketed(context)), StandardCharsets.UTF_8);
      JsonNode node = MAPPER.readTree(json);
      return node instanceof ObjectNode object ? object : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String unbracketed(String context) {
    return context.startsWith("[") && context.endsWith("]") ? context.substring(1, context.length() - 1) : context;
  }

  private static Optional<HttpServletRequest> request() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
        ? Optional.of(attributes.getRequest())
        : Optional.empty();
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
