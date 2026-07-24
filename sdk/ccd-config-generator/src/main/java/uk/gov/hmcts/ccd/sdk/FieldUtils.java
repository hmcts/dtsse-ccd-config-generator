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
    CCD cf = field.getAnnotation(CCD.class);

    return null != field.getAnnotation(JsonIgnore.class) || (null != cf && cf.ignore())
        || isFieldGatedOff(field);
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

  public static List<Field> getCaseFields(Class caseDataClass) {
    List<Field> fields = new ArrayList<>();
    ReflectionUtils.doWithFields(caseDataClass, fields::add, field -> true);
    return fields.stream()
        .filter(f -> !isFieldIgnored(f))
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
