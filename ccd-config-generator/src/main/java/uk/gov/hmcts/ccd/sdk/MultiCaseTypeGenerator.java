package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.File;
import java.util.List;
import net.jodah.typetools.TypeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;

@Component
class MultiCaseTypeGenerator {
  private final List<CCDConfig<?, ?, ?>> configs;

  @Autowired
  public MultiCaseTypeGenerator(List<CCDConfig<?, ?, ?>> configs) {
    this.configs = configs;
  }

  public void generateCaseTypes(File outFolder) {
    Multimap<Class<?>, CCDConfig<?, ?, ?>>
        configsByDataClass = Multimaps
        .index(configs, x -> TypeResolver.resolveRawArguments(CCDConfig.class, x.getClass())[0]);
    for (Class<?> c : configsByDataClass.keySet()) {
      ConfigGenerator generator = new ConfigGenerator(configsByDataClass.get(c));
      File f = new File(outFolder, c.getSimpleName());
      f.mkdirs();
      generator.resolveConfig(f);
    }
  }

}
