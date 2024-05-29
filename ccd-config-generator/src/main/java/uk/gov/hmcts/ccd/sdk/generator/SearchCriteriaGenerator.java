package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

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
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class SearchCriteriaGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    final Path path = Paths.get(outputFolder.getPath(), "SearchCriteria.json");

    final List<Map<String, Object>> rows = config.getSearchCriteria().stream()
        .map(o -> toJson(config.getCaseType(), o))
        .collect(toList());
    mergeInto(path, rows, new AddMissing(), "CategoryID");//TODO update the primary keys
  }

  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, SearchCriteria searchCriteria) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("OtherCaseReference", searchCriteria.getOtherCaseReference());

    return field;
  }
}
