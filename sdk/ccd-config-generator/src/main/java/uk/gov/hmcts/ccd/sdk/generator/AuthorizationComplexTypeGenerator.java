package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;

import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ComplexTypeAuthorisation;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class AuthorizationComplexTypeGenerator<T, S, R extends HasRole>
    implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    List<ComplexTypeAuthorisation<R>> grants = config.getComplexTypeAuthorisations();
    if (grants == null || grants.isEmpty()) {
      return;
    }

    final List<Map<String, Object>> result = Lists.newArrayList();
    for (ComplexTypeAuthorisation<R> grant : grants) {
      result.add(toJson(config.getCaseType(), grant));
    }

    final Path path = Paths.get(outputFolder.getPath(), "AuthorisationComplexType.json");
    mergeInto(path, result, new AddMissing(),
        "CaseTypeID", "CaseFieldID", "ListElementCode", "UserRole");
  }

  private Map<String, Object> toJson(String caseType, ComplexTypeAuthorisation<R> grant) {
    Map<String, Object> row = JsonUtils.caseRow(caseType);
    row.put("CaseFieldID", grant.getCaseFieldId());
    row.put("ListElementCode", grant.getListElementCode());
    row.put("UserRole", grant.getRole().getRole());
    row.put("CRUD", Permission.toString(grant.getPermissions()));
    return row;
  }
}
