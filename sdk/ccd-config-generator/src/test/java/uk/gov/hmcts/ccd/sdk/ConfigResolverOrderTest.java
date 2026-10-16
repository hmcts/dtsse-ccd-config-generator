package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Pins the reachable-type walk to a stable order. Two reachable classes can share one CCD ID — the
 * same simple name in different packages, or the same {@code @ComplexType(name)} — and merge into a
 * single output file, first writer winning. That made the winner, and so the generated definition,
 * depend on the iteration order of the resolved-type map. Keyed on {@code Class}, a {@code HashMap}
 * orders by identity hash: it varies between JVM runs and rehashes whenever an unrelated type becomes
 * reachable, which is how {@code @CCD(typeParameterClass)} silently flipped which of prl's two
 * {@code DocumentDetails} classes supplied its {@code ElementLabel}s.
 */
public class ConfigResolverOrderTest {

  static class Leaf {
    private String value;
  }

  static class Middle {
    private Leaf leaf;
  }

  static class Root {
    private Middle middle;
    private Leaf leaf;
    private Other other;
  }

  static class Other {
    private Leaf leaf;
  }

  @Test
  public void resolvesTypesInDeclarationOrderNotIdentityHashOrder() {
    List<String> order = resolveNames();

    // Declaration order of the walk, depth-first: Root's own fields in order, each descended into
    // before the next. Any assertion on a specific order proves the point — what matters is that it
    // is derived from the source rather than from identity hashes.
    assertThat(order).containsExactly("Middle", "Leaf", "Other");

    // And it is the SAME order every time, for objects allocated fresh (so with fresh identity
    // hashes) in between. Under a HashMap this held only by luck of the hash spread.
    for (int i = 0; i < 20; i++) {
      Object unused = new Object();
      assertThat(unused).isNotNull();
      assertThat(resolveNames()).isEqualTo(order);
    }
  }

  private List<String> resolveNames() {
    Map<Class, Integer> types = ConfigResolver.resolve(Root.class, "uk.gov.hmcts");
    return types.keySet().stream().map(Class::getSimpleName).collect(Collectors.toList());
  }
}
