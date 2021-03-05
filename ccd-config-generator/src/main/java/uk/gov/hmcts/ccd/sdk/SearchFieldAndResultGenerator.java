package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.Search.SearchBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchField;


class SearchFieldAndResultGenerator {

  public static void generate(File root, String caseType, ConfigBuilderImpl builder) {
    generateFields(root, caseType, builder.searchInputFields, "SearchInputFields");
    generateFields(root, caseType, builder.searchResultFields, "SearchResultFields");
  }

  private static void generateFields(
          File root,
          String caseType,
          List<SearchBuilder> searchFields,
          String fileName
  ) {
    List<Map<String, Object>> result = Lists.newArrayList();

    int displayOrder = 1;
    for (SearchBuilder wb : searchFields) {
      Search search = wb.build();

      for (SearchField field : search.getFields()) {
        Map<String, Object> map = buildField(caseType, field.getId(), field.getLabel(),
                displayOrder++);

        result.add(map);
      }
    }
    Path output = Paths.get(root.getPath(), fileName + ".json");
    JsonUtils.mergeInto(output, result, new AddMissing(), "CaseFieldID");
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
