package uk.gov.hmcts.ccd.sdk.generator;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.Maps;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.HasCode;
import uk.gov.hmcts.ccd.sdk.api.HasLabel;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.generator.JsonUtils.AddMissing;

@Component
class FixedListGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    File dir = root.toPath().resolve("FixedLists").toFile();
    dir.mkdir();

    Map<String, List<Enum<?>>> definitions = new LinkedHashMap<>();
    for (Class<?> c : config.getTypes().keySet()) {
      ComplexType complexType = c.getAnnotation(ComplexType.class);
      if (c.isEnum() && (complexType == null || complexType.generate())) {
        definitions
            .computeIfAbsent(c.getSimpleName(), ignored -> new ArrayList<>())
            .addAll(Arrays.asList((Enum<?>[]) c.getEnumConstants()));
      }
    }

    config
        .getFixedLists()
        .forEach(
            (id, values) -> {
              List<Enum<?>> existing = definitions.get(id);
              if (existing == null) {
                definitions.put(id, new ArrayList<>(values));
                return;
              }
              if (!existing.equals(values)) {
                throw new IllegalStateException("Conflicting fixed-list definitions for " + id);
              }
            });

    for (Map.Entry<String, List<Enum<?>>> definition : definitions.entrySet()) {
      List<Map<String, Object>> fields = new ArrayList<>();
      int order = 1;
      for (Enum<?> enumConstant : definition.getValue()) {
        CCD annotation =
            enumConstant.getDeclaringClass().getField(enumConstant.name()).getAnnotation(CCD.class);

        Object label =
            annotation != null && annotation.numericListElement() > Integer.MIN_VALUE
                ? annotation.numericListElement()
                : enumConstant instanceof HasLabel
                    ? ((HasLabel) enumConstant).getLabel()
                    : annotation == null
                        ? enumConstant
                        : !isNullOrEmpty(annotation.label())
                            ? annotation.label()
                            : !isNullOrEmpty(annotation.hint()) ? annotation.hint() : enumConstant;

        Map<String, Object> value = Maps.newHashMap();
        fields.add(value);
        value.put("ListElement", label);
        value.put("LiveFrom", JsonUtils.DEFAULT_LIVE_FROM);
        value.put("ID", definition.getKey());
        value.put(
            "ListElementCode",
            annotation != null && !annotation.numericListElementCode().isEmpty()
                ? new BigDecimal(annotation.numericListElementCode())
                : enumConstant instanceof HasCode coded ? coded.getCode() : enumConstant);
        if (annotation == null || !annotation.omitDisplayOrder()) {
          value.put(
              "DisplayOrder",
              annotation != null && annotation.displayOrder() > 0
                  ? annotation.displayOrder()
                  : order);
        }
        order++;
      }

      Path path = Paths.get(dir.getPath(), definition.getKey() + ".json");
      JsonUtils.mergeIntoPreservingGeneratedOccurrences(
          path, fields, new AddMissing(), "ListElementCode");
    }
  }
}
