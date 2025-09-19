package uk.gov.hmcts.ccd.sdk;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.reflections.ReflectionUtils.withName;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.reflections.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;

public class FieldUtils {

  public static boolean isFieldIgnored(Field field) {
    CCD cf = field.getAnnotation(CCD.class);

    return null != field.getAnnotation(JsonIgnore.class) || (null != cf && cf.ignore());
  }

  public static List<Field> getCaseFields(Class caseDataClass) {
    return ReflectionUtils.getAllFields(caseDataClass)
        .stream()
        .filter(f -> !isFieldIgnored(f))
        .collect(Collectors.toList());
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
    return ReflectionUtils
      .getAllFields(caseDataClass, withName(fieldName))
      .stream()
      .findFirst()
      .map(f -> f.getAnnotation(JsonUnwrapped.class));
  }
}
