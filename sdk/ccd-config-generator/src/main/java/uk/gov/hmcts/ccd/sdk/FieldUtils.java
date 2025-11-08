package uk.gov.hmcts.ccd.sdk;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.util.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;

public class FieldUtils {

  public static boolean isFieldIgnored(Field field) {
    CCD cf = field.getAnnotation(CCD.class);

    return null != field.getAnnotation(JsonIgnore.class) || (null != cf && cf.ignore());
  }

  public static List<Field> getCaseFields(Class caseDataClass) {
    List<Field> fields = new ArrayList<>();
    ReflectionUtils.doWithFields(caseDataClass, fields::add, field -> true);
    return fields.stream()
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
    Field field = ReflectionUtils.findField(caseDataClass, fieldName);
    if (field == null) {
      return Optional.empty();
    }
    ReflectionUtils.makeAccessible(field);
    return Optional.ofNullable(field.getAnnotation(JsonUnwrapped.class));
  }
}
