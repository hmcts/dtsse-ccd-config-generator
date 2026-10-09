package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work a submit handler wants done once its event has committed, before the event's response is
 * returned: sending a request the user expects to see the effect of when their page reloads, for
 * instance. Handlers register it through the {@code afterCommit(Runnable)} of what they are given;
 * the runtime runs what was registered. Nothing runs if the event does not commit, and work that
 * fails is logged, not retried: the event has committed and its response is still returned.
 */
public final class AfterCommit {

  private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

  private final List<Runnable> actions = new ArrayList<>();
  private final boolean commits;

  public AfterCommit() {
    this(true);
  }

  private AfterCommit(boolean commits) {
    this.commits = commits;
  }

  /** For a handler nothing commits after, such as a start handler, which may register nothing. */
  public static AfterCommit none() {
    return new AfterCommit(false);
  }

  public void add(Runnable action) {
    if (!commits) {
      throw new IllegalStateException("Nothing commits after this handler; afterCommit is for submit handlers");
    }
    actions.add(action);
  }

  /** For the runtime, once the event has committed. */
  public void run() {
    for (Runnable action : actions) {
      try {
        action.run();
      } catch (RuntimeException e) {
        log.warn("Work registered to run after the event committed failed and is not retried", e);
      }
    }
  }
}
