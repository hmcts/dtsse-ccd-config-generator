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
import uk.gov.hmcts.ccd.sdk.api.SearchParty;
import uk.gov.hmcts.ccd.sdk.api.SearchPartyField;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class SearchPartyGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    final List<Map<String, Object>> result = Lists.newArrayList();
    for (SearchParty field : config.getSearchParties()) {
      for (SearchPartyField searchPartyField : field.getFields()) {
        Map<String, Object> map = toJson(config.getCaseType(), searchPartyField);
        result.add(map);
      }
    }

    final Path path = Paths.get(outputFolder.getPath(), "SearchParty.json");
    mergeInto(path, result, new AddMissing(), "CaseTypeID", "SearchPartyName");
  }

  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, SearchPartyField searchParty) {
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
