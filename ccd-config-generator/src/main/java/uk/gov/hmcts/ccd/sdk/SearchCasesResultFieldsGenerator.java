package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.SearchCases.SearchCasesBuilder;
import uk.gov.hmcts.ccd.sdk.api.SearchCasesResultField;

class SearchCasesResultFieldsGenerator {

  public static void generate(File root, String caseType, List<SearchCasesBuilder> searchCasesBuilders) {
    List<SearchCasesResultField> fields = searchCasesBuilders.stream()
        .flatMap(builder -> builder.build().getFields().stream())
        .collect(Collectors.toList());

    List<Map<String, Object>> jsonFields = IntStream
        .range(0, fields.size())
        .mapToObj(i -> buildField(caseType, fields.get(i), i + 1))
        .collect(Collectors.toList());

    if (!jsonFields.isEmpty()) {
      Path tabDir = Paths.get(root.getPath(), "SearchCasesResultFields");
      tabDir.toFile().mkdirs();
      Path output = tabDir.resolve("SearchCasesResultFields.json");
      JsonUtils.mergeInto(output, jsonFields, new AddMissing(), "CaseFieldID");
    }
  }

  private static Map<String, Object> buildField(String caseType, SearchCasesResultField field, int order) {
    Map<String, Object> object = Maps.newHashMap();
    object.put("LiveFrom", "01/01/2017");
    object.put("CaseTypeID", caseType);
    object.put("UserRole", "");
    object.put("CaseFieldID", field.getId());
    object.put("Label", field.getLabel());
    object.put("DisplayOrder", order);
    object.put("UseCase", "orgcases");

    if (null != field.getListElementCode()) {
      object.put("ListElementCode", field.getListElementCode());
    }
    if (null != field.getDisplayContextParameter()) {
      object.put("DisplayContextParameter", field.getListElementCode());
    }
    if (null != field.getResultsOrdering()) {
      object.put("ResultsOrdering", field.getListElementCode());
    }
    return object;
  }
}

