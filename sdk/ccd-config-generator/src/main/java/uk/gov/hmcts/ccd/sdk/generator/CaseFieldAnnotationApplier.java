package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.base.Strings;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.api.CCD;

final class CaseFieldAnnotationApplier {

  private CaseFieldAnnotationApplier() {
  }

  static void applyCcdAnnotation(Map<String, Object> target, CCD annotation) {
    if (annotation == null) {
      return;
    }

    if (!target.containsKey("Label")) {
      target.put("Label", annotation.label().isEmpty() ? " " : annotation.label());
    }
    if (!Strings.isNullOrEmpty(annotation.hint())) {
      target.put("HintText", annotation.hint());
    }
    if (!Strings.isNullOrEmpty(annotation.regex())) {
      target.put("RegularExpression", annotation.regex());
    }
    if (annotation.showSummaryContent()) {
      target.put("ShowSummaryContentOption", "Y");
    }
    if (!annotation.searchable()) {
      target.put("Searchable", "N");
    }
    if (!Strings.isNullOrEmpty(annotation.showCondition())) {
      target.put("FieldShowCondition", annotation.showCondition());
    }
    if (annotation.displayOrder() > 0) {
      target.put("DisplayOrder", annotation.displayOrder());
    }
    if (!Strings.isNullOrEmpty(annotation.categoryID())) {
      target.put("CategoryID", annotation.categoryID());
    }
    if (annotation.min() > Integer.MIN_VALUE) {
      target.put("Min", annotation.min());
    }
    if (annotation.max() < Integer.MAX_VALUE) {
      target.put("Max", annotation.max());
    }
  }

  static void ensureDefaultLabel(Map<String, Object> target) {
    target.putIfAbsent("Label", " ");
  }
}
