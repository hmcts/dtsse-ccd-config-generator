package uk.gov.hmcts.ccd.sdk;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;

public class FieldUtils {

  public static boolean isFieldIgnored(Field field) {
    return isFieldIgnored(field.getDeclaringClass(), field);
  }

  /**
   * Whether this field contributes no rows when reached through {@code owner} — {@code @JsonIgnore},
   * {@code @CCD(ignore = true)} or an inactive {@code @CCD(gate)}, taking {@code owner}'s
   * {@code @CCD(member)} override into account (see {@link CCD#member()}).
   *
   * @param owner the class the field is being reached through
   * @param field the reflected case-data field
   * @return true when the field is not part of the generated definition here
   */
  public static boolean isFieldIgnored(Class<?> owner, Field field) {
    CCD cf = ccdAnnotation(owner, field);

    return null != field.getAnnotation(JsonIgnore.class) || (null != cf && cf.ignore())
        || (null != cf && !EnvironmentGate.matches(cf.gate()));
  }

  /**
   * Whether the field declares a {@code @CCD(gate)} whose environment predicate does not match at
   * generation time. Such a field is treated exactly like {@code @CCD(ignore = true)}: every
   * generator that reflects fields through {@link #getCaseFields} skips it, so it emits no rows.
   *
   * @param field the reflected case-data field
   * @return true when a declared gate is inactive in the current environment
   */
  public static boolean isFieldGatedOff(Field field) {
    CCD cf = field.getAnnotation(CCD.class);
    return null != cf && !EnvironmentGate.matches(cf.gate());
  }

  /**
   * The {@code @CCD} configuration for this field as reached through {@code owner}: the class-level
   * {@code @CCD(member = "<name>")} override declared by {@code owner} or a subclass of the field's
   * declaring class, else the field's own annotation.
   *
   * <p>An override REPLACES rather than merges with the field's annotation, so the subclass states
   * the member's whole configuration and nothing carries over invisibly — the same all-or-nothing
   * rule as re-declaring the field would give. See {@link CCD#member()} for why the class-level form
   * exists.
   *
   * @param owner the class the field is being reached through
   * @param field the reflected case-data field
   * @return the effective annotation, or null when neither form is present
   */
  public static CCD ccdAnnotation(Class<?> owner, Field field) {
    Class<?> declaring = field.getDeclaringClass();
    // Only a class BELOW the declaration can override it, and the nearest such class wins.
    for (Class<?> c = owner; c != null && c != declaring; c = c.getSuperclass()) {
      for (CCD candidate : c.getDeclaredAnnotationsByType(CCD.class)) {
        if (candidate.member().equals(field.getName())) {
          return candidate;
        }
      }
    }
    return field.getAnnotation(CCD.class);
  }

  /**
   * Fails generation when a class-level {@code @CCD(member)} names nothing it can configure: a field
   * the class declares itself (annotate it directly), or one no supertype declares (a typo, or a
   * member since renamed). Either way the override is silently inert, and a definition quietly
   * missing a {@code FieldShowCondition} is far worse than a build that says so.
   */
  private static void validateMemberOverrides(Class<?> owner) {
    for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
      for (CCD override : c.getDeclaredAnnotationsByType(CCD.class)) {
        if (override.member().isEmpty()) {
          continue;
        }
        Field target = ReflectionUtils.findField(c, override.member());
        if (target == null || target.getDeclaringClass() == c) {
          throw new IllegalStateException(
              ("@CCD(member = \"%s\") on %s must name a field a SUPERCLASS declares; %s")
                  .formatted(override.member(), c.getName(), target == null
                      ? "no supertype declares that field"
                      : "%s declares it itself, so annotate the field directly"
                          .formatted(c.getSimpleName())));
        }
      }
    }
  }

  /**
   * The case-data members of a class: every instance field it or a supertype declares, minus those
   * ignored.
   *
   * <p>Static fields are not case data — they belong to the class, not to a case, and Jackson never
   * serialises them — so they are excluded. Nothing declares them {@code @JsonIgnore}, because on a
   * hand-written model the question never arises; but a retrofitted model is a real service's code,
   * and real classes carry constants and loggers beside their data. sscs's Lombok {@code @Slf4j}
   * loggers emitted {@code ComplexTypes} rows of {@code FieldType=Logger}, and
   * {@code CorrespondenceDetails}' private {@code DateTimeFormatter} one of
   * {@code FieldType=DateTimeFormatter} — types no CCD definition can name. {@link
   * #collectGatedOffFieldIds} already filtered them; this is the same predicate, applied where the
   * rows are actually produced.
   *
   * <p>A field a subclass redeclares <em>hides</em> the superclass one: Java resolves the name to
   * the subclass's field and Jackson sees a single property, so only the most-derived declaration is
   * case data. {@link ReflectionUtils#doWithFields} walks subclass-first and reports both, which
   * emitted two rows for one property — sscs's {@code JointParty} redeclares {@code Entity}'s
   * {@code id}/{@code identity}/{@code name}/{@code address}/{@code contact} to give each a
   * {@code @JsonProperty("jointParty…")} ID, and the hidden {@code Entity} fields emitted a second
   * row apiece under their own unprefixed IDs. Keeping the first declaration seen keeps the one Java
   * itself resolves to.
   *
   * @param caseDataClass the case-data or complex-type class
   * @return its non-static, non-ignored, non-hidden fields, most-derived declaration first
   */
  public static List<Field> getCaseFields(Class caseDataClass) {
    validateMemberOverrides(caseDataClass);
    List<Field> fields = new ArrayList<>();
    Set<String> declared = new LinkedHashSet<>();
    ReflectionUtils.doWithFields(caseDataClass,
        field -> {
          if (declared.add(field.getName())) {
            fields.add(field);
          }
        },
        field -> !Modifier.isStatic(field.getModifiers()));
    return fields.stream()
        .filter(f -> !isFieldIgnored(caseDataClass, f))
        .collect(Collectors.toList());
  }

  /**
   * The CCD field IDs of every {@code @CCD(gate)} field whose gate is inactive in the current
   * environment, walking the case-data class and its {@code @JsonUnwrapped} clusters exactly as the
   * generators do (so a gated-off unwrapped member is collected under its prefixed ID). Generators
   * that place fields by ID — CaseEventToFields, the AuthorisationCaseField event/tab/search loops,
   * CaseTypeTab, WorkBasket/Search — consult this set to skip a gated-off placement, avoiding a
   * dangling row that references a CaseField the reflection filter suppressed.
   *
   * <p>Empty whenever no field declares a gate (the common case), so gating is a no-op and every
   * existing definition regenerates byte-identically.
   *
   * @param caseDataClass the case-data class
   * @return the set of gated-off CCD field IDs, empty when nothing is gated off
   */
  public static Set<String> gatedOffFieldIds(Class caseDataClass) {
    Set<String> ids = new LinkedHashSet<>();
    collectGatedOffFieldIds(caseDataClass, "", ids);
    return ids;
  }

  private static void collectGatedOffFieldIds(Class dataClass, String prefix, Set<String> ids) {
    List<Field> fields = new ArrayList<>();
    ReflectionUtils.doWithFields(dataClass,
        fields::add, field -> !Modifier.isStatic(field.getModifiers()));
    for (Field field : fields) {
      // A JsonIgnore/ignore field is dropped everywhere already; do not record it as gated.
      if (null != field.getAnnotation(JsonIgnore.class)) {
        continue;
      }
      CCD cf = field.getAnnotation(CCD.class);
      if (null != cf && cf.ignore()) {
        continue;
      }
      JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);
      if (null != unwrapped) {
        String newPrefix = prefix.isEmpty()
            ? unwrapped.prefix()
            : prefix.concat(capitalize(unwrapped.prefix()));
        collectGatedOffFieldIds(field.getType(), newPrefix, ids);
      } else if (isFieldGatedOff(field)) {
        ids.add(getFieldId(field, prefix));
      }
    }
  }

  public static String getFieldId(Field field) {
    return getFieldId(field, null);
  }

  public static String getFieldId(Field field, String prefix) {
    JsonProperty j = field.getAnnotation(JsonProperty.class);
    String name = j != null ? j.value() : field.getName();

    return null == prefix || prefix.isEmpty() ? name : prefix.concat(capitalize(name));
  }

  public static Optional<JsonUnwrapped> isUnwrappedField(Class caseDataClass, String fieldName) {
    Field field = ReflectionUtils.findField(caseDataClass, fieldName);
    if (field == null) {
      return Optional.empty();
    }
    ReflectionUtils.makeAccessible(field);
    return Optional.ofNullable(field.getAnnotation(JsonUnwrapped.class));
  }
}
