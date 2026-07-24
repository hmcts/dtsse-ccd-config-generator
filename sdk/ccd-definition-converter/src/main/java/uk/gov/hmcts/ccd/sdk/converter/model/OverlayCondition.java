package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.Map;
import lombok.Value;

/**
 * An environment predicate equivalent to a per-environment overlay file suffix.
 *
 * <p>JSON-based services activate overlay files (e.g. {@code CaseEvent-prod.json}) by glob
 * inclusion/exclusion when building each environment's spreadsheet. The converter instead
 * emits the overlay rows guarded by a runtime check of an environment variable, so running
 * {@code generateCCDConfig} in different environments reproduces the per-environment JSON.
 *
 * <p>Parsed from the CLI syntax {@code suffix=[!]ENV_VAR:value}, e.g.
 * {@code prod=CCD_DEF_ENV:prod} or {@code nonprod=!CCD_DEF_ENV:prod}.
 */
@Value
public class OverlayCondition {

  String envVar;
  String expectedValue;
  boolean negated;

  /**
   * Parses the CLI predicate syntax.
   *
   * @param spec a predicate of the form {@code [!]ENV_VAR:value}
   * @return the parsed condition
   * @throws IllegalArgumentException if the spec is malformed
   */
  public static OverlayCondition parse(String spec) {
    boolean negated = spec.startsWith("!");
    String body = negated ? spec.substring(1) : spec;
    int colon = body.indexOf(':');
    if (colon <= 0 || colon == body.length() - 1) {
      throw new IllegalArgumentException(
          "Overlay predicate must be of the form [!]ENV_VAR:value but was '" + spec + "'");
    }
    return new OverlayCondition(body.substring(0, colon), body.substring(colon + 1), negated);
  }

  /**
   * Evaluates the predicate against system properties first, then the environment — the
   * same order the generated {@code EnvironmentFlags} class uses, so the round-trip test
   * matrix can flip environments in-process.
   *
   * @return whether the overlay's rows are active
   */
  public boolean isActive() {
    String value = System.getProperty(envVar, System.getenv().getOrDefault(envVar, ""));
    return evaluate(value);
  }

  /**
   * THE single evaluation point for this predicate: matches {@code expectedValue}
   * case-insensitively against the value resolved from {@code env}, XORed with
   * {@code negated}. An absent or null env entry resolves to the empty string, so this
   * method never throws on a missing or null-valued entry.
   *
   * @param env the environment to resolve {@code envVar} from, e.g. a test-supplied map
   * @return whether the overlay's rows are active
   */
  public boolean isActive(Map<String, String> env) {
    String value = env == null ? null : env.get(envVar);
    return evaluate(value);
  }

  private boolean evaluate(String value) {
    boolean matches = expectedValue.equalsIgnoreCase(value == null ? "" : value);
    return negated != matches;
  }

  /**
   * The canonical {@code [!]ENV_VAR:value} spelling of this predicate — the inverse of
   * {@link #parse}. Used as the value of {@code @CCD(gate = ...)} when an overlay-only CaseData
   * field is emitted as a generation-time gated member: the SDK's {@code EnvironmentGate} parses it
   * with the identical grammar and resolution order, so both sides agree in every environment.
   *
   * @return the predicate as an SDK gate expression
   */
  public String toExpression() {
    return (negated ? "!" : "") + envVar + ":" + expectedValue;
  }
}
