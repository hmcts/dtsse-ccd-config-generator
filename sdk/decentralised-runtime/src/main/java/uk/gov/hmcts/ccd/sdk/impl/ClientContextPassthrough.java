package uk.gov.hmcts.ccd.sdk.impl;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Carries the Client-Context header through a submission as CCD carries it through callbacks. The context the
 * submission arrived with is sent to each callback; a context a callback returns replaces it for later callbacks and
 * goes back to CCD on the submit response, which CCD merges with the context it sent.
 *
 * <p>The callbacks run on the submission's request thread, so the request holds the returned context.
 */
public final class ClientContextPassthrough {

  public static final String CLIENT_CONTEXT = "Client-Context";

  private static final String RETURNED = ClientContextPassthrough.class.getName() + ".returned";

  private ClientContextPassthrough() {
  }

  /** The context the latest callback returned, else the one the submission arrived with. */
  public static Optional<String> current() {
    return returned().or(() -> request()
        .map(request -> request.getHeader(CLIENT_CONTEXT))
        .filter(ClientContextPassthrough::present));
  }

  /** The context the latest callback in this submission returned, if any did. */
  public static Optional<String> returned() {
    return request().map(request -> (String) request.getAttribute(RETURNED));
  }

  /** Records a context a callback returned. A callback that returns none leaves the current context as it was. */
  public static void returned(String clientContext) {
    if (present(clientContext)) {
      request().ifPresent(request -> request.setAttribute(RETURNED, clientContext));
    }
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
