package uk.gov.hmcts.ccd.sdk.generator;

import static com.google.common.base.Strings.isNullOrEmpty;

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
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class FixedListGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    File dir = root.toPath().resolve("FixedLists").toFile();
    dir.mkdir();

    for (Class<?> c : config.getTypes().keySet()) {
      ComplexType complexType = c.getAnnotation(ComplexType.class);
      if (c.isEnum() && (complexType == null || complexType.generate())) {
        List<Map<String, Object>> fields = Lists.newArrayList();

        int order = 1;
        for (Object enumConstant : c.getEnumConstants()) {
          String enumName = ((Enum<?>)enumConstant).name();
          CCD annotation = c.getField(enumName).getAnnotation(CCD.class);

          // use the enum label field, or the @CCD label, or the @CCD hint, or the enumConstant
          Object label = enumConstant instanceof HasLabel
              ? ((HasLabel) enumConstant).getLabel()
              : annotation == null
                  ? enumConstant
                  : !isNullOrEmpty(annotation.label())
                      ? annotation.label()
                      : !isNullOrEmpty(annotation.hint())
                          ? annotation.hint()
                          : enumConstant;

          Map<String, Object> value = Maps.newHashMap();
          fields.add(value);
          value.put("ListElement", label);
          value.put("LiveFrom", "01/01/2017");
          value.put("ID", c.getSimpleName());
          value.put("ListElementCode", enumConstant);
          value.put("DisplayOrder", order++);
        }

        Path path = Paths.get(dir.getPath(), c.getSimpleName() + ".json");
        JsonUtils.mergeInto(path, fields, new AddMissing(), "ListElementCode");
      }
    }
  }
}
