package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.ConfigBuilderImpl;
import uk.gov.hmcts.ccd.sdk.JsonUtils;
import uk.gov.hmcts.ccd.sdk.types.Role;
import uk.gov.hmcts.ccd.sdk.types.WorkBasketResult;
import uk.gov.hmcts.ccd.sdk.types.WorkBasketResult.WorkBasketResultBuilder;
import uk.gov.hmcts.ccd.sdk.types.WorkBasketResultField;

public class WorkBasketResultFieldsGenerator {

  public static <T, R extends Role, S> void generate(File root, String caseType,
      ConfigBuilderImpl<T, S, R> builder) {

    List<Map<String, Object>> result = Lists.newArrayList();

    int displayOrder = 1;
    for (WorkBasketResultBuilder wb : builder.workBasketResultFields) {
      WorkBasketResult workBasketResult = wb.build();

      for (WorkBasketResultField field : workBasketResult.getFields()) {
        Map<String, Object> map = buildField(caseType, field.getId(), field.getLabel(),
            displayOrder++);

        result.add(map);
      }
    }
    Path output = Paths.get(root.getPath(), "WorkBasketResultFields.json");
    JsonUtils.mergeInto(output, result, "CaseFieldID");
  }

  private static Map<String, Object> buildField(String caseType, String fieldId, String label,
      int displayOrder) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("CaseFieldID", fieldId);
    field.put("Label", label);
    field.put("DisplayOrder", displayOrder);
    return field;
  }
}

