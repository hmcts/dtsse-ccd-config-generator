package uk.gov.hmcts.ccd.sdk.api;

import java.util.Set;

/**
 * Restricted builder for service events.
 * Service events do not use CCD's UI configuration — no pages, fields, show conditions,
 * or display options. This builder exposes only the methods that apply to service events.
 */
public class ServiceEventBuilder<T, R extends HasRole, S> {

  private final Event.EventBuilder<T, R, S> delegate;

  public ServiceEventBuilder(Event.EventBuilder<T, R, S> delegate) {
    this.delegate = delegate;
  }

  public ServiceEventBuilder<T, R, S> name(String n) {
    delegate.name(n);
    return this;
  }

  public ServiceEventBuilder<T, R, S> description(String description) {
    delegate.description(description);
    return this;
  }

  public ServiceEventBuilder<T, R, S> grant(Permission permission, R... roles) {
    delegate.grant(permission, roles);
    return this;
  }

  public ServiceEventBuilder<T, R, S> grant(Set<Permission> crud, R... roles) {
    delegate.grant(crud, roles);
    return this;
  }

  public ServiceEventBuilder<T, R, S> grant(HasAccessControl... accessControls) {
    delegate.grant(accessControls);
    return this;
  }

  public ServiceEventBuilder<T, R, S> grantHistoryOnly(R... roles) {
    delegate.grantHistoryOnly(roles);
    return this;
  }

  public ServiceEventBuilder<T, R, S> explicitGrants() {
    delegate.explicitGrants();
    return this;
  }

  public ServiceEventBuilder<T, R, S> retries(int... retries) {
    delegate.retries(retries);
    return this;
  }

  public ServiceEventBuilder<T, R, S> publishToCamunda() {
    delegate.publishToCamunda();
    return this;
  }

  public ServiceEventBuilder<T, R, S> publishToCamunda(boolean publishToCamunda) {
    delegate.publishToCamunda(publishToCamunda);
    return this;
  }

  public ServiceEventBuilder<T, R, S> ttlIncrement(Integer ttlIncrement) {
    delegate.ttlIncrement(ttlIncrement);
    return this;
  }
}
