package uk.gov.hmcts.ccd.sdk.json;

import org.springframework.http.HttpHeaders;
import uk.gov.hmcts.reform.ccd.client.model.CallbackRequest;

/**
 * Runs a JSON definition's local about-to-submit or submitted callback in place of the controller method its URL
 * maps to.
 *
 * <p>By default a local callback URL is resolved to the Spring POST handler mapped to exactly that path. A service
 * whose callbacks share templated paths, or that dispatches internally, registers a bean of this type instead. The
 * runtime asks it first for every local callback, both when the definition is validated at startup and when the
 * event is submitted.
 */
public interface JsonCallbackHandler {

  /**
   * Whether this handler runs the callback at the given path.
   *
   * @param callbackPath the callback URL from the definition with the local placeholder removed, for example
   *                     {@code /cases/callbacks/version/V_1/about-to-submit}
   */
  boolean handles(String callbackPath);

  /**
   * Runs the callback.
   *
   * @param callbackPath the path {@link #handles(String)} accepted
   * @param request      the callback request CCD would have sent
   * @param headers      the {@code Authorization} and {@code ServiceAuthorization} headers of the submission, and its
   *                     {@code Client-Context} when it has one, as CCD would send them
   * @return the callback response body, in the shape the controller would have returned it. Return it in a
   *     {@link org.springframework.http.ResponseEntity} to send a {@code Client-Context} header back, as a
   *     controller would to CCD.
   */
  Object handle(String callbackPath, CallbackRequest request, HttpHeaders headers);
}
