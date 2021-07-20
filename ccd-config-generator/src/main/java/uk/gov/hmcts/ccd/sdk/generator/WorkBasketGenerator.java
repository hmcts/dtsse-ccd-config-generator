package uk.gov.hmcts.ccd.sdk.generator;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.WorkBasket;
import uk.gov.hmcts.ccd.sdk.api.WorkBasketField;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class WorkBasketGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    generateFields(root, config.getCaseType(), config.getWorkBasketInputFields(), "WorkBasketInputFields");
    generateFields(root, config.getCaseType(), config.getWorkBasketResultFields(), "WorkBasketResultFields");
  }

  private static void generateFields(File root, String caseType,
      List<WorkBasket> workBasketFields, String fileName) {
    List<Map<String, Object>> result = Lists.newArrayList();

    int displayOrder = 1;
    for (WorkBasket workBasket : workBasketFields) {
      for (WorkBasketField field : workBasket.getFields()) {
        Map<String, Object> map = buildField(caseType, field.getId(), field.getLabel(),
            displayOrder++, field.getListElementCode(), field.getShowCondition());

        result.add(map);
      }
    }
    Path output = Paths.get(root.getPath(), fileName + ".json");
    JsonUtils.mergeInto(output, result, new AddMissing(), "CaseFieldID");
  }

  private static Map<String, Object> buildField(String caseType, String fieldId, String label,
      int displayOrder, String listElementCode, String showCondition) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("CaseFieldID", fieldId);
    field.put("Label", label);

    if (!isNullOrEmpty(listElementCode)) {
      field.put("ListElementCode", listElementCode);
    }
    if (!isNullOrEmpty(showCondition)) {
      field.put("FieldShowCondition", showCondition);
    }

    field.put("DisplayOrder", displayOrder);
    return field;
  }
}

