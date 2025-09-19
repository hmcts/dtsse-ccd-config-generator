package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.SearchCriteria;
import uk.gov.hmcts.ccd.sdk.api.SearchCriteriaField;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class SearchCriteriaGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    final List<Map<String, Object>> result = Lists.newArrayList();
    for (SearchCriteria field : config.getSearchCriteria()) {
      for (SearchCriteriaField searchCriteriaField : field.getFields()) {
        Map<String, Object> map = toJson(config.getCaseType(), searchCriteriaField);
        result.add(map);
      }
    }

    final Path path = Paths.get(outputFolder.getPath(), "SearchCriteria.json");
    mergeInto(path, result, new AddMissing(), "CaseTypeID", "OtherCaseReference");
  }

  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, SearchCriteriaField searchCriteria) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("OtherCaseReference", searchCriteria.getOtherCaseReference());

    return field;
  }
}
