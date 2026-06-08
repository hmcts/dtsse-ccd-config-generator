package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.mergeInto;
import static uk.gov.hmcts.ccd.sdk.generator.JsonUtils.nullToEmpty;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.AccessTypeRole;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class AccessTypeRoleGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    final Path path = Paths.get(outputFolder.getPath(), "AccessTypeRole.json");

    final List<Map<String, Object>> rows = config.getAccessTypeRoles().stream()
        .map(role -> toJson(config.getCaseType(), role))
        .collect(toList());
    mergeInto(path, rows, new AddMissing(), false,
        "AccessTypeID", "OrganisationalRoleName", "GroupRoleName");
  }

  private static Map<String, Object> toJson(String caseType, AccessTypeRole accessTypeRole) {
    Map<String, Object> row = JsonUtils.caseRow(caseType);

    row.put("AccessTypeID", accessTypeRole.getAccessTypeId());
    row.put("OrganisationProfileID", nullToEmpty(accessTypeRole.getOrganisationProfileId()));
    row.put("OrganisationalRoleName", nullToEmpty(accessTypeRole.getOrganisationalRoleName()));
    row.put("GroupRoleName", nullToEmpty(accessTypeRole.getGroupRoleName()));
    row.put("CaseAssignedRoleField", nullToEmpty(accessTypeRole.getCaseAssignedRoleField()));
    row.put("GroupAccessEnabled", JsonUtils.yesNo(accessTypeRole.isGroupAccessEnabled()));
    row.put("CaseAccessGroupIDTemplate", nullToEmpty(accessTypeRole.getCaseAccessGroupIdTemplate()));
    row.put("LiveTo", nullToEmpty(accessTypeRole.getLiveTo()));

    return row;
  }
}
