package uk.gov.hmcts.ccd.sdk.generator;

import java.util.Map;
import uk.gov.hmcts.ccd.sdk.api.Field;

final class CaseEventFieldMetadataApplier {

  private CaseEventFieldMetadataApplier() {
  }

  static void apply(
      Map<String, Object> target,
      Field field,
      String labelColumn,
      String hintColumn) {

    if (field.getShowCondition() != null) {
      target.put("FieldShowCondition", field.getShowCondition());
    }

    if (labelColumn != null && field.getCaseEventFieldLabel() != null) {
      target.put(labelColumn, field.getCaseEventFieldLabel());
    }

    if (hintColumn != null && field.getCaseEventFieldHint() != null) {
      target.put(hintColumn, field.getCaseEventFieldHint());
    }

    if (field.isRetainHiddenValue()) {
      target.put("RetainHiddenValue", "Y");
    }
  }
}
