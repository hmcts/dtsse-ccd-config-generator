package uk.gov.hmcts.ccd.sdk.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalUser;
import uk.gov.hmcts.ccd.sdk.runtime.ExternalUserResolver;

/** Resolves the user starting an external event from the token CCD forwards, as submission does. */
@Component
@RequiredArgsConstructor
class IdamExternalUserResolver implements ExternalUserResolver {

  private final IdamService idam;

  @Override
  public ExternalUser resolve(String authorisation) {
    return idam.retrieveUser(authorisation).toExternalUser();
  }
}
