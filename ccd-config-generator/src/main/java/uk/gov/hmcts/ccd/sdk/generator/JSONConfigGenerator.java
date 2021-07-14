package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
public class JSONConfigGenerator<T, S, R extends HasRole> {

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
    List<Map<String, Object>> fields = Lists.newArrayList();
    fields.add(Map.of(
        "LiveFrom", "01/01/2017",
        "ID", builder.getCaseType(),
        "Name", builder.getCaseName(),
        "Description", builder.getCaseDesc(),
        "JurisdictionID", builder.getJurId(),
        "SecurityClassification", "Public"
    ));
    Path output = Paths.get(outputfolder.getPath(),"CaseType.json");
    JsonUtils.mergeInto(output, fields, new JsonUtils.AddMissing(), "ID");
  }

  private void generateJurisdiction(File outputfolder, ResolvedCCDConfig<T, S, R> builder) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    fields.add(ImmutableMap.of(
        "LiveFrom", "01/01/2017",
        "ID", builder.getJurId(),
        "Name", builder.getJurName(),
        "Description", builder.getJurDesc()
    ));
    Path output = Paths.get(outputfolder.getPath(),"Jurisdiction.json");
    JsonUtils.mergeInto(output, fields, new JsonUtils.AddMissing(), "ID");
  }
}
