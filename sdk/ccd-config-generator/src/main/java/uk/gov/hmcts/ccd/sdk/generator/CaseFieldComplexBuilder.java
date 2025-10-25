package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.FieldUtils.getCaseFields;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.getFieldId;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.Label;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

final class CaseFieldComplexBuilder {

  private final String caseTypeId;
  private final List<Map<String, Object>> fields;

  private CaseFieldComplexBuilder(String caseTypeId) {
    this.caseTypeId = caseTypeId;
    this.fields = Lists.newArrayList();
  }

  static List<Map<String, Object>> build(Class<?> dataClass, String caseTypeId) {
    return build(dataClass, caseTypeId, "");
  }

  static List<Map<String, Object>> build(Class<?> dataClass, String caseTypeId, String idPrefix) {
    CaseFieldComplexBuilder builder = new CaseFieldComplexBuilder(caseTypeId);
    builder.appendFields(dataClass, idPrefix);
    return builder.fields;
  }

  private void appendFields(Class<?> dataClass, String idPrefix) {
    for (Field field : getCaseFields(dataClass)) {
      appendField(dataClass, field, idPrefix);
    }
  }

  private void appendField(Class<?> ownerClass, Field field, String idPrefix) {
    JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);
    if (unwrapped != null) {
      appendUnwrapped(field, idPrefix, unwrapped);
      return;
    }

    String id = getFieldId(field, idPrefix);
    Label label = field.getAnnotation(Label.class);
    CaseFieldAnnotationApplier.applyLabelAnnotation(fields, caseTypeId, label);

    Map<String, Object> fieldInfo = CaseFieldGenerator.getField(caseTypeId, id);
    fields.add(fieldInfo);

    populateFieldMetadata(fieldInfo, ownerClass, field);
  }

  private void appendUnwrapped(Field field, String currentPrefix, JsonUnwrapped unwrapped) {
    String prefix = currentPrefix.isEmpty()
        ? unwrapped.prefix()
        : currentPrefix.concat(StringUtils.capitalize(unwrapped.prefix()));
    appendFields(field.getType(), prefix);
  }

  static void populateFieldMetadata(
      Map<String, Object> target, Class<?> ownerClass, Field field) {
    CCD annotation = field.getAnnotation(CCD.class);
    CaseFieldAnnotationApplier.applyCcdAnnotation(target, annotation);
    CaseFieldAnnotationApplier.ensureDefaultLabel(target);

    if (annotation != null && annotation.typeOverride() != FieldType.Unspecified) {
      target.put("FieldType", annotation.typeOverride().toString());
      if (!Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
        target.put("FieldTypeParameter", annotation.typeParameterOverride());
      }
      return;
    }

    CaseFieldTypeResolver.applyFieldType(ownerClass, field, target, annotation);
  }
}
