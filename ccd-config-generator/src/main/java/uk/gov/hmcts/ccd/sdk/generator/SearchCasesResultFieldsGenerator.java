package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.SearchCasesResultField;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class SearchCasesResultFieldsGenerator<T, S, R extends HasRole> implements
    ConfigGenerator<T, S, R> {

  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    List<SearchCasesResultField> fields = config.getSearchCaseResultFields().stream()
        .flatMap(x -> x.getFields().stream())
        .collect(Collectors.toList());

    List<Map<String, Object>> jsonFields = IntStream
        .range(0, fields.size())
        .mapToObj(i -> buildField(config.getCaseType(), fields.get(i), i + 1))
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
    object.put("LiveFrom", "03/02/2021");
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

