package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Resolves a non-collection field against the class it is reached through, so a field whose type is a
 * type variable contributes the type argument the subclass supplied rather than the variable's
 * erasure.
 *
 * <p>A generic wrapper hierarchy is the common shape: sscs declares
 * {@code AbstractDocument<D extends AbstractDocumentDetails>} with a {@code private D value} and
 * subclasses it per document kind. Reading {@code field.getType()} yields the bound for every one of
 * them, which broke the definition in both directions at once — the bound became reachable and
 * emitted its members under an ID no definition row names, while each type argument became reachable
 * nowhere and emitted no rows at all. Only a subclass that happened to be separately reachable
 * through some concrete declaration survived, which is why the failure looked arbitrary.
 */
public class ConfigResolverGenericFieldTest {

  static class Details {
    private String shared;
  }

  static class WelshDetails extends Details {
    private String welshOnly;
  }

  static class DwpDetails extends Details {
    private String dwpOnly;
  }

  static class Wrapper<D extends Details> {
    private D value;
  }

  static class WelshWrapper extends Wrapper<WelshDetails> {
  }

  static class DwpWrapper extends Wrapper<DwpDetails> {
  }

  static class Root {
    private WelshWrapper welsh;
    private DwpWrapper dwp;
  }

  @Test
  public void resolvesAGenericFieldToTheTypeArgumentNotItsBound() {
    List<String> names = resolveNames(Root.class);

    // Both type arguments are reached, each through its own subclass...
    assertThat(names).contains("WelshDetails", "DwpDetails");
    // ...and the bound is not reached in its own right. Nothing declares a Details, so emitting rows
    // for it would declare a complex type no field references.
    assertThat(names).doesNotContain("Details");
  }

  static class RawRoot {
    private Wrapper<?> wrapper;
  }

  @Test
  public void fallsBackToTheBoundWhenNoTypeArgumentIsSupplied() {
    // A wildcard supplies no class to resolve the variable to, so the bound is the only answer
    // available — the erasure behaviour, kept for exactly this case rather than applied to every
    // field.
    assertThat(resolveNames(RawRoot.class)).contains("Details");
  }

  private List<String> resolveNames(Class<?> root) {
    Map<Class, Integer> types = ConfigResolver.resolve(root, "uk.gov.hmcts");
    return types.keySet().stream().map(Class::getSimpleName).collect(Collectors.toList());
  }
}
