package uk.gov.hmcts.ccd.sdk.generator;

import static java.util.stream.Collectors.toList;
import static org.springframework.util.CollectionUtils.isEmpty;
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
import uk.gov.hmcts.ccd.sdk.api.AccessType;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
public class AccessTypeGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(final File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    if (isEmpty(config.getAccessTypes())) {
      return;
    }

    final Path path = Paths.get(outputFolder.getPath(), "AccessType.json");

    final List<Map<String, Object>> rows = config.getAccessTypes().stream()
        .map(o -> toJson(config.getCaseType(), o))
        .collect(toList());
    // Keyed on AccessTypeID + OrganisationProfileID to match the definition store, whose
    // AccessTypesValidator treats (caseType, jurisdiction, accessTypeId, organisationProfileId) as
    // the identity. On AccessTypeID alone the same access type offered to a second organisation
    // profile would merge into the first row instead of being emitted.
    mergeInto(path, rows, new AddMissing(), false, "AccessTypeID", "OrganisationProfileID");
  }

  private static Map<String, Object> toJson(String caseType, AccessType accessType) {
    Map<String, Object> row = JsonUtils.caseRow(caseType);

    row.put("AccessTypeID", accessType.getAccessTypeId());
    // nullToEmpty because OrganisationProfileID is a merge key: a JSON null here reads back as null
    // on the next run and NPEs in JsonUtils.mergeInto's primary-key comparison.
    row.put("OrganisationProfileID", nullToEmpty(accessType.getOrganisationProfileId()));
    row.put("AccessMandatory", JsonUtils.yesNo(accessType.isAccessMandatory()));
    row.put("AccessDefault", JsonUtils.yesNo(accessType.isAccessDefault()));
    row.put("Display", JsonUtils.yesNo(accessType.isDisplay()));
    row.put("Description", nullToEmpty(accessType.getDescription()));
    row.put("HintText", nullToEmpty(accessType.getHintText()));
    row.put("DisplayOrder", accessType.getDisplayOrder());
    row.put("LiveTo", nullToEmpty(accessType.getLiveTo()));

    return row;
  }
}
