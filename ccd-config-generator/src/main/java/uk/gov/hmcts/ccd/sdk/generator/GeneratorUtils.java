package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

public final class GeneratorUtils {

  private GeneratorUtils() {
  }

  public static <T, R extends HasRole, S> List<Event<T, R, S>> sortDisplayOrderByEventName(
      final Collection<Event<T, R, S>> events) {

    final AtomicInteger displayOrder = new AtomicInteger();

    return events.stream()
      .sorted((event1, event2) -> event1.getName().compareToIgnoreCase(event2.getName()))
      .map(event -> {
        event.setDisplayOrder(displayOrder.incrementAndGet());
        return event;
      })
      .collect(toList());
  }

  public static <T, R extends HasRole, S> boolean hasAnyDisplayOrder(final Collection<Event<T, R, S>> events) {
    return events.stream().anyMatch(event -> event.getDisplayOrder() != -1);
  }
}
