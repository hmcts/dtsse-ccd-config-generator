package uk.gov.hmcts.ccd.sdk.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.SystemEventResult;

class TestSystemEventExecutorTest {

  enum State { Issued }

  private final TestSystemEventExecutor executor = new TestSystemEventExecutor("Pending");

  @Test
  void runsEachActionInTheCurrentStateAndRecordsItsResult() {
    List<String> seenStates = new ArrayList<>();
    var issue = SystemEventResult.withStateTransition("issue", "Issue", State.Issued);
    var note = SystemEventResult.withoutStateTransition("note", "Note");

    executor.execute(1L, UUID.randomUUID(), context -> {
      seenStates.add(context.currentState());
      return issue;
    });
    executor.execute(1L, UUID.randomUUID(), context -> {
      seenStates.add(context.currentState());
      return note;
    });

    assertThat(seenStates).containsExactly("Pending", "Issued");
    assertThat(executor.results()).containsExactly(issue, note);
  }

  @Test
  void rejectsAnActionThatReturnsNoResult() {
    assertThatThrownBy(() -> executor.execute(1L, UUID.randomUUID(), context -> null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("System event action must return a result");
  }
}
