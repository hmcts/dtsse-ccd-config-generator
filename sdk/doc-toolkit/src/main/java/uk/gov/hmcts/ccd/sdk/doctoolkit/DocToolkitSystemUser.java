package uk.gov.hmcts.ccd.sdk.doctoolkit;

/**
 * The user the toolkit fetches and uploads CDAM documents as. Services provide it as a bean,
 * usually backed by their system update user:
 *
 * <pre>{@code
 * @Bean
 * DocToolkitSystemUser docToolkitSystemUser(IdamService idam) {
 *   return () -> idam.retrieveSystemUpdateUserDetails().getAuthToken();
 * }
 * }</pre>
 */
@FunctionalInterface
public interface DocToolkitSystemUser {

  /**
   * A bearer token for the user, as sent in the {@code Authorization} header.
   */
  String authorisation();
}
