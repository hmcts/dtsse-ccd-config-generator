package uk.gov.hmcts.ccd.sdk;

/**
 * Evaluates a generation-time environment gate declared on a {@code @CCD(gate = ...)} field.
 *
 * <p>A gate makes a {@code CaseData} field (or complex-type member) part of the generated
 * definition only when an environment predicate matches at the moment {@code generateCCDConfig}
 * runs. It is the SDK counterpart of the per-environment overlay files hand-written definitions
 * activate by glob inclusion/exclusion when building each environment's spreadsheet: instead of
 * shipping the field in a {@code CaseField-prod.json} fragment, the field is annotated
 * {@code @CCD(gate = "CCD_DEF_JO:true")} and the generator emits — or omits — every row the field
 * owns (CaseField, AuthorisationCaseField, CaseEventToFields placements, CaseTypeTab, complex-type
 * members and any complex types reachable only through it) according to whether the gate matches.
 *
 * <p>The predicate grammar is {@code [!]ENV_VAR:value} — identical to the ccd-definition-converter's
 * {@code OverlayCondition} so a converted overlay suffix maps to the same expression on both sides:
 *
 * <ul>
 *   <li>{@code CCD_DEF_JO:true} — active when {@code CCD_DEF_JO} resolves to {@code true}
 *       (case-insensitively).</li>
 *   <li>{@code !CCD_DEF_ENV:prod} — active when {@code CCD_DEF_ENV} does <em>not</em> resolve to
 *       {@code prod}.</li>
 * </ul>
 *
 * <p>The variable is resolved from {@link System#getProperty(String)} first, then the process
 * environment — the same order the converter's {@code OverlayCondition} and the generated
 * {@code EnvironmentFlags} class use, so an in-process round-trip test can flip environments with a
 * system property and see both sides agree.
 */
final class EnvironmentGate {

  private EnvironmentGate() {
  }

  /**
   * Whether the gate predicate matches the current environment, so the gated field's rows should
   * be emitted. An empty or {@code null} expression is never a gate and always matches.
   *
   * @param expression the {@code [!]ENV_VAR:value} predicate, or empty/null for "no gate"
   * @return true when the field's rows should be emitted
   * @throws IllegalArgumentException if a non-empty expression is malformed
   */
  static boolean matches(String expression) {
    if (expression == null || expression.isEmpty()) {
      return true;
    }
    boolean negated = expression.startsWith("!");
    String body = negated ? expression.substring(1) : expression;
    int colon = body.indexOf(':');
    if (colon <= 0 || colon == body.length() - 1) {
      throw new IllegalArgumentException(
          "@CCD(gate) must be of the form [!]ENV_VAR:value but was '" + expression + "'");
    }
    String envVar = body.substring(0, colon);
    String expectedValue = body.substring(colon + 1);
    String value = System.getProperty(envVar, System.getenv().getOrDefault(envVar, ""));
    boolean valueMatches = expectedValue.equalsIgnoreCase(value);
    return negated != valueMatches;
  }
}
