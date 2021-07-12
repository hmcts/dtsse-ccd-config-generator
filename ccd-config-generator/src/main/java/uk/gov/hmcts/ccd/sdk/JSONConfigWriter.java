package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Table;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

@Component
public class JSONConfigWriter<T, S, R extends HasRole> {

  private final Collection<ConfigWriter<T, S, R>> writers;

  public JSONConfigWriter(Collection<ConfigWriter<T, S, R>> writers) {
    this.writers = writers;
  }

  void writeConfig(File outputfolder, ResolvedCCDConfig<T, S, R> config) {
    initOutputDirectory(outputfolder);
    outputfolder.mkdirs();

    for (ConfigWriter<T, S, R> writer : this.writers) {
      writer.write(outputfolder, config);
    }

    Table<String, R, Set<Permission>> eventPermissions = buildEventRolePermissions(config.events);
    AuthorisationCaseEventGenerator.generate(outputfolder, eventPermissions,
        config.builder.caseType);
    AuthorisationCaseFieldGenerator.generate(outputfolder, config, eventPermissions);

    generateJurisdiction(outputfolder, config.builder);
    generateCaseType(outputfolder, config.builder);
    StateGenerator.generate(outputfolder, config.builder.caseType, config.stateArg);
    AuthorisationCaseTypeGenerator.generate(outputfolder, config.builder.caseType, config.roleType);
    CaseTypeTabGenerator.generate(outputfolder, config.builder.caseType, config.builder);
    SearchCasesResultFieldsGenerator.generate(
        outputfolder, config.builder.caseType, config.builder.searchCaseResultFields);
    AuthorisationCaseStateGenerator.generate(outputfolder, config, eventPermissions,
        config.builder.stateRolePermissions);
    WorkBasketGenerator.generate(outputfolder, config.builder.caseType, config.builder);
    SearchFieldAndResultGenerator.generate(outputfolder, config.builder.caseType, config.builder);
    CaseRoleGenerator.generate(outputfolder, config.builder.caseType, config.roleType);
  }

  @SneakyThrows
  private void initOutputDirectory(File outputfolder) {
    if (outputfolder.exists() && outputfolder.isDirectory()) {
      MoreFiles.deleteRecursively(outputfolder.toPath(), RecursiveDeleteOption.ALLOW_INSECURE);
    }
    outputfolder.mkdirs();
  }

  private void generateCaseType(File outputfolder, ConfigBuilderImpl builder) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    fields.add(Map.of(
        "LiveFrom", "01/01/2017",
        "ID", builder.caseType,
        "Name", builder.caseName,
        "Description", builder.caseDesc,
        "JurisdictionID", builder.jurId,
        "SecurityClassification", "Public"
    ));
    Path output = Paths.get(outputfolder.getPath(),"CaseType.json");
    JsonUtils.mergeInto(output, fields, new JsonUtils.AddMissing(), "ID");
  }

  private void generateJurisdiction(File outputfolder, ConfigBuilderImpl builder) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    fields.add(ImmutableMap.of(
        "LiveFrom", "01/01/2017",
        "ID", builder.jurId,
        "Name", builder.jurName,
        "Description", builder.jurDesc
    ));
    Path output = Paths.get(outputfolder.getPath(),"Jurisdiction.json");
    JsonUtils.mergeInto(output, fields, new JsonUtils.AddMissing(), "ID");
  }

  Table<String, R, Set<Permission>> buildEventRolePermissions(List<Event<T, R, S>> events) {
    Table<String, R, Set<Permission>> eventRolePermissions = HashBasedTable.create();
    for (Event<T, R, S> event : events) {
      SetMultimap<R, Permission> grants = event.getGrants();
      for (R role : grants.keySet()) {
        eventRolePermissions.put(event.getId(), role, grants.get(role));
      }
    }
    return eventRolePermissions;
  }
}
