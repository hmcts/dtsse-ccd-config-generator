package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public class GeneratorUtilsTest {

  @Test
  public void shouldSetDisplayOrderByAlphabeticalNameOrder() {

    Event<Object, HasRole, Object> event1 = Event.builder().name("Az").build();
    Event<Object, HasRole, Object> event2 = Event.builder().name("t").build();
    Event<Object, HasRole, Object> event3 = Event.builder().name("ab").build();
    Event<Object, HasRole, Object> event4 = Event.builder().name("h").build();

    List<Event<Object, HasRole, Object>> events = asList(event1, event2, event3, event4);

    List<Event<Object, HasRole, Object>> results = GeneratorUtils.sortDisplayOrderByEventName(events);

    assertThat(results)
      .hasSize(4)
      .extracting(Event::getDisplayOrder, Event::getName)
      .containsExactly(
        tuple(1, "ab"),
        tuple(2, "Az"),
        tuple(3, "h"),
        tuple(4, "t")
      );
  }
}
