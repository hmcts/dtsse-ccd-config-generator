package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.SearchParty;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

@Component
public class SearchPartyGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    final Path path = Paths.get(outputFolder.getPath(), "SearchParty.json");

    final List<Map<String, Object>> rows = config.getSearchParties().stream()
      .map(o -> toJson(config.getCaseType(), o))
      .collect(toList());
    mergeInto(path, rows, new JsonUtils.AddMissing(), "CategoryID");//TODO update the primary keys
  }

  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, SearchParty searchParty) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("SearchPartyCollectionFieldName", searchParty.getSearchPartyCollectionFieldName());
    field.put("SearchPartyName", searchParty.getSearchPartyName());
    field.put("SearchPartyEmailAddress", searchParty.getSearchPartyEmailAddress());
    field.put("SearchPartyAddressLine1", searchParty.getSearchPartyAddressLine1());
    field.put("SearchPartyPostCode", searchParty.getSearchPartyPostCode());
    field.put("SearchPartyDOB", searchParty.getSearchPartyDOB());
    field.put("SearchPartyDOD", searchParty.getSearchPartyDOD());

    return field;
  }
}
