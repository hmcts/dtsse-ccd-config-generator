package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Maps;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseRoleToAccessProfile;
import uk.gov.hmcts.ccd.sdk.api.Categories;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.join;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

@Component
public class CategoriesGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {

    final Path path = Paths.get(outputFolder.getPath(), "Categories.json");

    final List<Map<String, Object>> rows = config.getCategories().stream()
        .map(o -> toJson(config.getCaseType(), o))
        .collect(toList());
    mergeInto(path, rows, new AddMissing(), "RoleName");
  }

  @SneakyThrows
  private static Map<String, Object> toJson(String caseType, Categories categories) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("CategoryID", categories.getCategoryID());
    field.put("CategoryLabel", categories.getCategoryLabel());
    field.put("DisplayOrder", categories.getDisplayOrder());
    field.put("ParentCategoryID", categories.getParentCategoryID());

    return field;
  }

}
