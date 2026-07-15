package uk.gov.hmcts.ccd.sdk.generator;

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
    List<SearchCasesResultField<R>> fields = config.getSearchCaseResultFields().stream()
        .flatMap(x -> x.getFields().stream())
        .toList();

    List<Map<String, Object>> jsonFields = IntStream
        .range(0, fields.size())
        .mapToObj(i -> buildField(config.getCaseType(), fields.get(i), i + 1))
        .collect(Collectors.toList());

    if (!jsonFields.isEmpty()) {
      Path tabDir = Paths.get(root.getPath(), "SearchCasesResultFields");
      tabDir.toFile().mkdirs();
      Path output = tabDir.resolve("SearchCasesResultFields.json");
      // UseCase and UserRole are part of the row identity: the same CaseFieldID legitimately appears
      // once per (UseCase, AccessProfile) it is exposed under. Keying only on CaseFieldID collapsed
      // those to one last-wins row. Both columns retain their historic values (UseCase=orgcases,
      // empty UserRole) unless a field opts in, so the extra keys never tie apart existing configs
      // and the emitted JSON stays byte-identical for them.
      JsonUtils.mergeInto(output, jsonFields, new AddMissing(), "CaseFieldID", "UseCase", "UserRole");
    }
  }

  private static <R extends HasRole> Map<String, Object> buildField(
      String caseType, SearchCasesResultField<R> field, int order) {
    Map<String, Object> object = JsonUtils.caseRow(caseType, "03/02/2021");
    object.put("UserRole", field.getUserRole() == null ? "" : field.getUserRole().getRole());
    object.put("CaseFieldID", field.getId());
    object.put("Label", field.getLabel());
    object.put("DisplayOrder", order);
    object.put("UseCase", field.getUseCase() == null ? "orgcases" : field.getUseCase());

    if (null != field.getListElementCode()) {
      object.put("ListElementCode", field.getListElementCode());
    }
    if (null != field.getDisplayContextParameter()) {
      object.put("DisplayContextParameter", field.getDisplayContextParameter());
    }
    if (null != field.getResultsOrdering()) {
      object.put("ResultsOrdering", field.getResultsOrdering());
    }
    return object;
  }
}
