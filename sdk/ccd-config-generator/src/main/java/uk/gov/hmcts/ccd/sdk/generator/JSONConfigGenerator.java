package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CaseType;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Jurisdiction;

@Component
public class JSONConfigGenerator<T, S, R extends HasRole> {

  private static final DateTimeFormatter CCD_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final Collection<ConfigGenerator<T, S, R>> writers;

  public JSONConfigGenerator(Collection<ConfigGenerator<T, S, R>> writers) {
    this.writers = writers;
  }

  public void writeConfig(File outputfolder, ResolvedCCDConfig<T, S, R> config) {
    initOutputDirectory(outputfolder);
    outputfolder.mkdirs();

    for (ConfigGenerator<T, S, R> writer : this.writers) {
      writer.write(outputfolder, config);
    }

    if (!config.isIncludeDefaultLiveFrom()) {
      JsonUtils.removePropertyFromJsonFiles(
          outputfolder,
          "LiveFrom",
          (path, row) -> (path.getFileName().toString().equals("RoleToAccessProfiles.json")
              && config.getCaseRoleToAccessProfiles().stream()
                  .filter(profile -> profile.isRetainLiveFrom())
                  .anyMatch(profile -> profile.getRoleName().equals(row.get("RoleName"))))
              || (path.getFileName().toString().equals("AuthorisationCaseType.json")
                  && config.getCaseTypeAuthorisationRolesWithLiveFrom().stream()
                      .map(HasRole::getRole)
                      .anyMatch(role -> role.equals(row.get("UserRole"))))
              || (path.getFileName().toString().equals("CaseRoles.json")
                  && config.isRetainCaseRoleLiveFrom())
      );
    }

    generateJurisdiction(outputfolder, config);
    generateCaseType(outputfolder, config);
  }

  @SneakyThrows
  private void initOutputDirectory(File outputfolder) {
    if (outputfolder.exists() && outputfolder.isDirectory()) {
      MoreFiles.deleteRecursively(outputfolder.toPath(), RecursiveDeleteOption.ALLOW_INSECURE);
    }
    outputfolder.mkdirs();
  }

  private void generateCaseType(File outputfolder, ResolvedCCDConfig<T, S, R> builder) {
    CaseType definition = builder.getCaseTypeDefinition();
    if (definition == null) {
      definition = CaseType.builder()
          .id(builder.getCaseType())
          .name(builder.getCaseName())
          .description(builder.getCaseDesc())
          .build();
    }
    Map<String, Object> row = Maps.newHashMap();
    row.put("LiveFrom", formatDate(definition.getLiveFrom()));
    row.put("ID", definition.getId());
    row.put("Name", definition.getName());
    row.put("Description", definition.getDescription());
    row.put("JurisdictionID", builder.getJurId());
    row.put("SecurityClassification", "Public");
    if (definition.getPrintableDocumentsUrl() != null) {
      row.put("PrintableDocumentsUrl", definition.getPrintableDocumentsUrl());
    }
    if (definition.getEnableForDeletion() != null) {
      row.put("EnableForDeletion", definition.getEnableForDeletion() ? "Yes" : "No");
    }
    if (definition.getRetriesTimeoutUrlPrintEvent() != null) {
      row.put("RetriesTimeoutURLPrintEvent", definition.getRetriesTimeoutUrlPrintEvent());
    }
    Path output = Paths.get(outputfolder.getPath(),"CaseType.json");
    JsonUtils.mergeInto(output, Lists.newArrayList(row), new JsonUtils.AddMissing(), "ID");
  }

  private void generateJurisdiction(File outputfolder, ResolvedCCDConfig<T, S, R> builder) {
    Jurisdiction definition = builder.getJurisdictionDefinition();
    if (definition == null) {
      definition = Jurisdiction.builder()
          .id(builder.getJurId())
          .name(builder.getJurName())
          .description(builder.getJurDesc())
          .build();
    }
    Map<String, Object> row = Maps.newHashMap();
    row.put("LiveFrom", formatDate(definition.getLiveFrom()));
    row.put("ID", definition.getId());
    row.put("Name", definition.getName());
    row.put("Description", definition.getDescription());
    if (definition.getShuttered() != null) {
      row.put("Shuttered", definition.getShuttered() ? "Yes" : "No");
    }
    Path output = Paths.get(outputfolder.getPath(),"Jurisdiction.json");
    JsonUtils.mergeInto(output, Lists.newArrayList(row), new JsonUtils.AddMissing(), "ID");
  }

  private String formatDate(LocalDate value) {
    return value.format(CCD_DATE);
  }
}
