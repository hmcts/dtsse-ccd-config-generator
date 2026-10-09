package uk.gov.hmcts.ccd.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Work registered to run after an event commits: run in order, each regardless of the others. */
public class AfterCommitTest {

    @Test
    public void runsWhatWasRegisteredInOrderAndCarriesOnPastWorkThatFails() {
        List<String> done = new ArrayList<>();
        AfterCommit afterCommit = new AfterCommit();
        afterCommit.add(() -> done.add("first"));
        afterCommit.add(() -> {
            throw new IllegalStateException("task management is down");
        });
        afterCommit.add(() -> done.add("third"));

        afterCommit.run();

        assertThat(done).containsExactly("first", "third");
    }

    @Test
    public void refusesWorkFromAHandlerNothingCommitsAfter() {
        assertThatThrownBy(() -> AfterCommit.none().add(() -> { }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("submit handlers");
    }

    @Test
    public void aStartHandlersPayloadRefusesWork() {
        EventPayload<String, String> payload = new EventPayload<>(1L, "data", null);

        assertThatThrownBy(() -> payload.afterCommit(() -> { }))
            .isInstanceOf(IllegalStateException.class);
    }
}
