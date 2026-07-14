package uk.gov.hmcts.ccd.sdk;

import static org.apache.commons.lang3.StringUtils.capitalize;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.util.ReflectionUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.CCDCollectionValue;

public class FieldUtils {

  public static boolean isFieldIgnored(Field field) {
    return isFieldIgnored(field, null);
  }

  public static boolean isFieldIgnored(Field field, Class<?> schemaProfile) {
    Optional<CCD> definition = getCCD(field, schemaProfile);
    boolean hasProfileMetadata = field.getAnnotationsByType(CCD.class).length > 0;

    return null != field.getAnnotation(JsonIgnore.class)
        || (hasProfileMetadata && definition.isEmpty())
        || definition.map(CCD::ignore).orElse(false);
  }

  public static List<Field> getCaseFields(Class caseDataClass) {
    return getCaseFields(caseDataClass, null);
  }

  public static List<Field> getCaseFields(Class caseDataClass, Class<?> schemaProfile) {
    Map<String, Field> fieldsById = new LinkedHashMap<>();
    for (Class<?> type = caseDataClass; type != null && type != Object.class;
         type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        String id = getFieldId(field, null, schemaProfile);
        Field selected = fieldsById.get(id);
        if (selected == null) {
          fieldsById.put(id, field);
        } else {
          validateRedeclaredField(id, selected, field, schemaProfile);
        }
      }
    }
    return fieldsById.values().stream()
        .filter(field -> !isFieldIgnored(field, schemaProfile))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private static void validateRedeclaredField(
      String id, Field selected, Field inherited, Class<?> schemaProfile) {
    if (!selected.getType().equals(inherited.getType())) {
      throw new IllegalStateException("Conflicting Java types for redeclared CCD field " + id
          + ": " + selected + " and " + inherited);
    }
    Optional<CCD> selectedDefinition = getCCD(selected, schemaProfile);
    Optional<CCD> inheritedDefinition = getCCD(inherited, schemaProfile);
    if (selectedDefinition.isPresent() && inheritedDefinition.isPresent()
        && !selectedDefinition.get().equals(inheritedDefinition.get())) {
      throw new IllegalStateException("Conflicting @CCD definitions for redeclared field " + id
          + ": " + selected + " and " + inherited);
    }
  }

  public static Optional<CCD> getCCD(AnnotatedElement element, Class<?> schemaProfile) {
    List<CCD> applicable = Arrays.stream(element.getAnnotationsByType(CCD.class))
        .filter(annotation -> appliesTo(annotation, schemaProfile))
        .toList();
    if (applicable.size() > 1) {
      throw new IllegalStateException("Multiple @CCD definitions apply to " + element
          + " for schema profile " + (schemaProfile == null ? "<default>" : schemaProfile.getName()));
    }
    return applicable.stream().findFirst();
  }

  private static boolean appliesTo(CCD annotation, Class<?> schemaProfile) {
    if (schemaProfile == null) {
      return annotation.includeInProfiles().length == 0;
    }
    boolean included = annotation.includeInProfiles().length == 0
        || Arrays.stream(annotation.includeInProfiles())
            .anyMatch(profile -> profile.isAssignableFrom(schemaProfile));
    return included && Arrays.stream(annotation.excludeFromProfiles())
        .noneMatch(profile -> profile.isAssignableFrom(schemaProfile));
  }

  public static Class<?> unwrapCollectionValueType(Class<?> elementType) {
    CCDCollectionValue wrapper = elementType.getAnnotation(CCDCollectionValue.class);
    if (wrapper == null) {
      return elementType;
    }
    Field valueField = ReflectionUtils.findField(elementType, wrapper.value());
    if (valueField == null) {
      throw new IllegalStateException("Collection wrapper %s has no '%s' field"
          .formatted(elementType.getName(), wrapper.value()));
    }
    return valueField.getType();
  }

  public static String getFieldId(Field field) {
    return getFieldId(field, null);
  }

  public static String getFieldId(Field field, String prefix) {
    return getFieldId(field, prefix, null);
  }

  public static String getFieldId(Field field, String prefix, Class<?> schemaProfile) {
    Optional<CCD> ccd = getCCD(field, schemaProfile);
    if (ccd.isPresent() && !ccd.get().id().isBlank()) {
      return ccd.get().id();
    }
    JsonProperty j = field.getAnnotation(JsonProperty.class);
    String name = j != null ? j.value() : field.getName();

    return null == prefix || prefix.isEmpty() ? name : prefix.concat(capitalize(name));
  }

  public static String getAuthorisationFieldId(
      Field field, String prefix, Class<?> schemaProfile) {
    Optional<CCD> ccd = getCCD(field, schemaProfile);
    if (ccd.isPresent() && !ccd.get().authorisationId().isBlank()) {
      return ccd.get().authorisationId();
    }
    return getFieldId(field, prefix, schemaProfile);
  }

  public static Optional<JsonUnwrapped> isUnwrappedField(Class caseDataClass, String fieldName) {
    if (caseDataClass == null) {
      return Optional.empty();
    }
    Field field = ReflectionUtils.findField(caseDataClass, fieldName);
    if (field == null) {
      return Optional.empty();
    }
    ReflectionUtils.makeAccessible(field);
    return Optional.ofNullable(field.getAnnotation(JsonUnwrapped.class));
  }
}
