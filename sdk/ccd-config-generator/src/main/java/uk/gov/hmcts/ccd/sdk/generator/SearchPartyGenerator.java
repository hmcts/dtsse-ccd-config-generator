package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
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
    mergeInto(path, result, new AddMissing(), "CaseTypeID", "QualifiedPartyName");
  }

  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, SearchPartyField searchParty) {
    Map<String, Object> field = JsonUtils.caseRow(caseType);

    String searchPartyCollectionFieldName = searchParty.getSearchPartyCollectionFieldName();
    String searchPartyName = searchParty.getSearchPartyName();

    String qualifiedPartyName = createQualifiedPartyName(searchPartyCollectionFieldName, searchPartyName);

    field.put("QualifiedPartyName", qualifiedPartyName);
    field.put("SearchPartyCollectionFieldName", searchPartyCollectionFieldName);
    field.put("SearchPartyName", searchPartyName);
    field.put("SearchPartyEmailAddress", searchParty.getSearchPartyEmailAddress());
    field.put("SearchPartyAddressLine1", searchParty.getSearchPartyAddressLine1());
    field.put("SearchPartyPostCode", searchParty.getSearchPartyPostCode());
    field.put("SearchPartyDOB", searchParty.getSearchPartyDOB());
    field.put("SearchPartyDOD", searchParty.getSearchPartyDOD());

    return field;
  }

  private static String createQualifiedPartyName(String searchPartyCollectionFieldName, String searchPartyName) {
    if (StringUtils.isNotBlank(searchPartyCollectionFieldName)) {
      return searchPartyCollectionFieldName + "/" + searchPartyName;
    } else {
      return searchPartyName;
    }
  }

}
