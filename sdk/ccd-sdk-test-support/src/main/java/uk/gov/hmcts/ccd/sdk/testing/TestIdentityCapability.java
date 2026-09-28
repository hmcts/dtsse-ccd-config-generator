package uk.gov.hmcts.ccd.sdk.testing;

import feign.Capability;
import feign.Client;

/**
 * Applied by Spring Cloud OpenFeign to every Feign client, so an application's own IDAM, S2S and
 * role assignment clients are answered by test support without the application faking them.
 */
public final class TestIdentityCapability implements Capability {

  private final TestActors actors;

  TestIdentityCapability(TestActors actors) {
    this.actors = actors;
  }

  @Override
  public Client enrich(Client client) {
    return new TestIdentityClient(client, actors);
  }
}
