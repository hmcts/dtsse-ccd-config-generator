package uk.gov.hmcts.ccd.sdk.generator;

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
import uk.gov.hmcts.ccd.sdk.api.Search;
import uk.gov.hmcts.ccd.sdk.api.SearchField;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class SearchFieldAndResultGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    generateFields(root, config.caseType, config.searchInputFields, "SearchInputFields");
    generateFields(root, config.caseType, config.searchResultFields, "SearchResultFields");
  }

  private static void generateFields(
          File root,
          String caseType,
          List<Search> searchFields,
          String fileName
  ) {
    List<Map<String, Object>> result = Lists.newArrayList();

    int displayOrder = 1;
    for (Search search : searchFields) {
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
