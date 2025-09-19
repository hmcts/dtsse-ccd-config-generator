package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;


public class BulkScanEventTypeBuilderImpl<T, R extends HasRole, S> extends EventTypeBuilderImpl<T, R, S> {

  private final String name;

  public BulkScanEventTypeBuilderImpl(ResolvedCCDConfig<T, S, R> config,
                                      Map<String, List<Event.EventBuilder<T, R, S>>> events, String id, String name) {
    super(config, events, id, null, null);
    this.name = name;
  }

  protected Event.EventBuilder<T, R, S> build(Set<S> preStates, Set<S> postStates) {
    Event.EventBuilder<T, R, S> result = super.build(preStates, postStates);

    result.name(name);
    result.description(name);
    result.endButtonLabel(null);
    return result;
  }

}
