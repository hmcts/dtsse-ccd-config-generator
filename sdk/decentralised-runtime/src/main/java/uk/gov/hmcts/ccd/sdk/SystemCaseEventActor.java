package uk.gov.hmcts.ccd.sdk;

import java.util.Objects;
import uk.gov.hmcts.reform.idam.client.models.UserInfo;

/**
 * The authenticated actor that caused a service to perform an in-process case event.
 *
 * <p>This is an attribution value supplied by trusted application code. Applications must construct it from an
 * identity established by their authentication boundary; the system event service does not authenticate it again.</p>
 */
public sealed interface SystemCaseEventActor
    permits SystemCaseEventActor.IdamUser, SystemCaseEventActor.Service {

  /** An IDAM user whose identity has already been authenticated by the application. */
  record IdamUser(UserInfo identity) implements SystemCaseEventActor {

    public IdamUser {
      Objects.requireNonNull(identity, "identity");
    }
  }

  /** An S2S service whose identity has already been authenticated by the application. */
  record Service(String serviceId) implements SystemCaseEventActor {

    public Service {
      Objects.requireNonNull(serviceId, "serviceId");
    }
  }
}
