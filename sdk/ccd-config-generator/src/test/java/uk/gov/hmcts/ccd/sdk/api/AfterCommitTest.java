package uk.gov.hmcts.ccd.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AfterCommitTest {

    @Test
    public void workThatFailsDoesNotStopTheRest() {
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
}
