package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
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

  public static File ensureDirectory(File root, String... segments) {
    File current = root;
    for (String segment : segments) {
      current = new File(current, segment);
    }
    current.mkdirs();
    return current;
  }

  /**
   * The CCD type ID a class is emitted and referenced under: its {@code @ComplexType(name)} when set,
   * otherwise its simple class name. Used for both ComplexTypes and generated FixedLists, so a class
   * can carry a Java-conventional name while preserving its original CCD ID.
   */
  public static String typeId(Class<?> type) {
    ComplexType complexType = type.getAnnotation(ComplexType.class);
    return complexType != null && !complexType.name().isEmpty()
        ? complexType.name() : type.getSimpleName();
  }
}
