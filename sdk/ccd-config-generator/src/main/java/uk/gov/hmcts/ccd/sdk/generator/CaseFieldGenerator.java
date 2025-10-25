package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.FieldUtils.getCaseFields;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.getFieldId;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.isUnwrappedField;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
class CaseFieldGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  // The field type set from code always takes precedence,
  // so eg. if a field changes type it gets updated.
  private static final ImmutableSet<String> OVERWRITES_FIELDS = ImmutableSet.of();

  @Override
  public void write(
      File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> fields = toComplex(config.getCaseClass(), config.getCaseType());

    Map<String, Object> history = getField(config.getCaseType(), "caseHistory");
    history.put("Label", " ");
    history.put("FieldType", "CaseHistoryViewer");
    fields.add(history);

    fields.addAll(getExplicitFields(config));

    Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, fields, new JsonUtils.OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId) {
    return CaseFieldComplexBuilder.build(dataClass, caseTypeId);
  }

  public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId, String idPrefix) {
    return CaseFieldComplexBuilder.build(dataClass, caseTypeId, idPrefix);
  }

  private static <T, S, R extends HasRole> List<Map<String, Object>> getExplicitFields(
      ResolvedCCDConfig<T, S, R> config) {
    Map<String, uk.gov.hmcts.ccd.sdk.api.Field> explicitFields = Maps.newHashMap();
    for (Event event : config.getEvents().values()) {
      List<uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder> fc = event.getFields()
          .getExplicitFields();

      for (uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder fieldBuilder : fc) {
        uk.gov.hmcts.ccd.sdk.api.Field field = fieldBuilder.build();
        explicitFields.put(field.getId(), field);
      }
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (String fieldId : explicitFields.keySet()) {
      Optional<JsonUnwrapped> unwrapped = isUnwrappedField(config.getCaseClass(), fieldId);
      // Don't export inbuilt metadata fields. Ignore unwrapped complex types
      if (fieldId.matches("\\[.+\\]") || unwrapped.isPresent()) {
        continue;
      }

      final uk.gov.hmcts.ccd.sdk.api.Field field = explicitFields.get(fieldId);
      Map<String, Object> fieldData = getField(config.getCaseType(), fieldId);
      result.add(fieldData);

      Optional<Field> caseField = findCaseField(config.getCaseClass(), fieldId);
      CCD annotation = caseField.map(candidate -> candidate.getAnnotation(CCD.class)).orElse(null);

      CaseFieldAnnotationApplier.applyCcdAnnotation(fieldData, annotation);
      CaseFieldAnnotationApplier.ensureDefaultLabel(fieldData);

      if (!Strings.isNullOrEmpty(field.getLabel())) {
        fieldData.put("Label", field.getLabel());
      }

      if (field.getType() != null) {
        fieldData.put("FieldType", field.getType());
      } else if (caseField.isPresent()) {
        CaseFieldTypeResolver.applyFieldType(config.getCaseClass(), caseField.get(), fieldData, annotation);
      } else {
        fieldData.put("FieldType", "Label");
      }

      if (field.getFieldTypeParameter() != null) {
        fieldData.put("FieldTypeParameter", field.getFieldTypeParameter());
      }
    }


    return result;
  }

  public static Map<String, Object> getField(String caseType, String id) {
    Map<String, Object> result = JsonUtils.caseRow(caseType);
    result.put("ID", id);
    result.put("SecurityClassification", "Public");
    return result;
  }

  private static Optional<Field> findCaseField(Class<?> caseClass, String fieldId) {
    return getCaseFields(caseClass)
        .stream()
        .filter(candidate -> getFieldId(candidate).equals(fieldId))
        .findFirst();
  }

}
