package uk.gov.hmcts.ccd.sdk;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;

public class FieldUtils {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    return getFieldId(field.getDeclaringClass(), field, prefix);
  }

  public static String getFieldId(Class<?> ownerClass, Field field, String prefix) {
    JsonProperty j = field.getAnnotation(JsonProperty.class);
    String name = j != null ? j.value() : getJsonPropertyName(ownerClass, field.getName());

    return null == prefix || prefix.isEmpty() ? name : prefix.concat(capitalize(name));
  }

  static String getJsonPropertyName(Class<?> ownerClass, String javaName) {
    if (AnnotatedElementUtils.findMergedAnnotation(ownerClass, JsonNaming.class) == null) {
      return javaName;
    }
    return MAPPER.getSerializationConfig().introspect(MAPPER.constructType(ownerClass))
        .findProperties().stream()
        .filter(property -> property.getInternalName().equals(javaName))
        .map(BeanPropertyDefinition::getName)
        .findFirst().orElse(javaName);
  }

  public static Optional<JsonUnwrapped> isUnwrappedField(Class caseDataClass, String fieldName) {
    Field field = ReflectionUtils.findField(caseDataClass, fieldName);
    if (field == null) {
      return getCaseFields(caseDataClass).stream()
          .filter(candidate -> candidate.isAnnotationPresent(JsonUnwrapped.class))
          .filter(candidate -> getFieldId(caseDataClass, candidate, null).equals(fieldName))
          .findFirst()
          .map(candidate -> candidate.getAnnotation(JsonUnwrapped.class));
    }
    ReflectionUtils.makeAccessible(field);
    return Optional.ofNullable(field.getAnnotation(JsonUnwrapped.class));
  }
}
