package uk.gov.hmcts.ccd.sdk.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Work a submit handler registers to run once its event has committed, before the response is
 * returned. Nothing runs if the event does not commit.
 */
public final class AfterCommit {

  private final List<Runnable> actions = new ArrayList<>();

  public void add(Runnable action) {
    actions.add(action);
  }

  /** For the runtime, once the event has committed. */
  public void run() {
    actions.forEach(Runnable::run);
  }
}
