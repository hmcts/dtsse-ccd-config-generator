package uk.gov.hmcts.ccd.sdk.bundling.cdam;

/**
 * Supplies the credentials the built-in CDAM adapters and the job worker present to downstream
 * services: the consuming service's system user bearer token and its service-to-service token.
 *
 * <p>Background and scheduled bundling never uses an end-user token — the consuming service
 * provides a system user with the correct RBAC for bundling, and both tokens are acquired on
 * demand through this port. Implementations may cache tokens in memory only, for their validity
 * window; tokens must never be persisted, logged, or placed on a durable job record. Spring
 * auto-configuration wiring this port to IDAM and the S2S provider arrives in a later phase; the
 * port is the stable contract adapters code against.
 */
public interface BundlingAuthenticationProvider {

  /**
   * The system user's bearer token, acquired on demand. The value is sent verbatim as the
   * {@code Authorization} header, so it must carry any required scheme prefix (for example
   * {@code Bearer }).
   *
   * @return the system user authorisation header value
   */
  String systemUserToken();

  /**
   * The service-to-service token, acquired on demand. The value is sent verbatim as the
   * {@code ServiceAuthorization} header.
   *
   * @return the service authorisation header value
   */
  String serviceToken();
}
