package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work a submit handler registers to run once its event has committed, before the response is
 * returned. Nothing runs if the event does not commit; work that fails is logged, not retried.
 */
public final class AfterCommit {

  private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

  private final List<Runnable> actions = new ArrayList<>();

  public void add(Runnable action) {
    actions.add(action);
  }

  /** For the runtime, once the event has committed. */
  public void run() {
    for (Runnable action : actions) {
      try {
        action.run();
      } catch (RuntimeException e) {
        log.warn("Work registered to run after the event committed failed", e);
      }
    }
  }
}
