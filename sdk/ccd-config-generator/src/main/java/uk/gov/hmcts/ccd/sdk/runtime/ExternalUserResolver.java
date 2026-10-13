package uk.gov.hmcts.ccd.sdk.runtime;

import uk.gov.hmcts.ccd.sdk.api.external.ExternalUser;

/**
 * Resolves the Authorization header CCD forwards with a callback to the user an external event's
 * start handler is given. The decentralised runtime provides one backed by IDAM.
 */
@FunctionalInterface
public interface ExternalUserResolver {
  ExternalUser resolve(String authorisation);
}
