package uk.gov.hmcts.ccd.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the CCD state ID that the SDK writes into the generated definition for a given
 * {@code State} enum constant, honouring {@code @JsonProperty} on the constant.
 *
 * <p><b>Why this exists.</b> Historically every generator serialised a state constant using its
 * raw {@code toString()} (the constant name), ignoring {@code @JsonProperty}. That was inconsistent
 * and, for some services, wrong:
 *
 * <ul>
 *   <li><b>Consistency.</b> {@link FieldUtils#getFieldId(Field)} already honours
 *       {@code @JsonProperty} for case fields; states were the one place the SDK ignored it.</li>
 *   <li><b>Correctness.</b> At runtime the case state value is serialised by Jackson, which
 *       <em>does</em> honour {@code @JsonProperty} — so a consumer whose state enum carried
 *       {@code @JsonProperty} was previously generating definition state IDs (e.g.
 *       {@code CASE_MANAGEMENT}) that could never match the state values their service actually
 *       writes (e.g. {@code PREPARE_FOR_HEARING}). The old behaviour was a latent bug, not a
 *       contract.</li>
 *   <li><b>Retrofit.</b> It lets services migrating from hand-written definitions (e.g. FPL, whose
 *       enum reconciles {@code CASE_MANAGEMENT → @JsonProperty("PREPARE_FOR_HEARING")}) reuse their
 *       existing {@code State} enum instead of maintaining a parallel one.</li>
 *   <li><b>Default-safe.</b> An enum without {@code @JsonProperty} on its constants resolves exactly
 *       as before ({@code toString()}), so existing definitions regenerate byte-identically.</li>
 * </ul>
 *
 * <p>Reflection to read the annotation always goes via {@link Enum#name()} — never
 * {@code toString()} — so an enum whose {@code toString()} is overridden to differ from the constant
 * name (e.g. an SSCS-style {@code @JsonValue toString()} returning the lowercase id) does not throw
 * {@link NoSuchFieldException}. When no {@code @JsonProperty} is present the resolution falls back to
 * {@code toString()}, which both preserves today's behaviour and supports such {@code @JsonValue}
 * enums (whose {@code toString()} already returns the id).
 */
public final class StateId {

  private static final Map<Object, String> CACHE = new ConcurrentHashMap<>();

  private StateId() {
  }

  /**
   * The CCD state ID for a state enum constant: the constant's {@code @JsonProperty} value when
   * present and non-empty, otherwise its {@code toString()}.
   *
   * @param enumConstant a {@code State} enum constant (any {@link Enum}); {@code null} yields
   *                     {@code null}
   * @return the resolved CCD state ID
   */
  public static String of(Object enumConstant) {
    if (enumConstant == null) {
      return null;
    }
    return CACHE.computeIfAbsent(enumConstant, StateId::resolve);
  }

  private static String resolve(Object enumConstant) {
    if (enumConstant instanceof Enum<?> e) {
      try {
        Field field = e.getDeclaringClass().getField(e.name());
        JsonProperty jsonProperty = field.getAnnotation(JsonProperty.class);
        if (jsonProperty != null && !Strings.isNullOrEmpty(jsonProperty.value())) {
          return jsonProperty.value();
        }
      } catch (NoSuchFieldException ignored) {
        // Fall back to toString() if the enum field cannot be resolved reflectively.
      }
    }
    return enumConstant.toString();
  }
}
