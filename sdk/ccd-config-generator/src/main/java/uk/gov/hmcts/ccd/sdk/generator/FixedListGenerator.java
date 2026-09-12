package uk.gov.hmcts.ccd.sdk.generator;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // A list is emitted only when some field or complex-type member the definition contains
    // references its ID as a FieldTypeParameter. Reachability of the enum CLASS is not enough: a
    // field may declare an enum and then declare itself to be something else
    // (@CCD(typeOverride = FieldType.Text) over a DirectionType field), or hold it purely as an
    // in-Java value, and neither produces a list in the definition. See
    // CaseFieldGenerator#referencedTypeParameters.
    Set<String> referenced = CaseFieldGenerator.referencedTypeParameters(config);

    for (Class<?> c : config.getTypes().keySet()) {
      ComplexType complexType = c.getAnnotation(ComplexType.class);
      if (c.isEnum() && (complexType == null || complexType.generate())) {
        // The FixedLists ID (and output file name) is the enum's @ComplexType(name) when set,
        // otherwise its simple class name. This lets a generated enum carry a Java-conventional
        // PascalCase class name while preserving the original CCD list ID as the wire ID.
        String listId = complexType != null && !isNullOrEmpty(complexType.name())
            ? complexType.name() : c.getSimpleName();
        if (!referenced.contains(listId)) {
          continue;
        }
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
          // HintText is its own column, carrying text the definition states alongside the label rather
          // than instead of it: Civil's InterestClaimFrom labels FROM_CLAIM_SUBMIT_DATE "The date you
          // submit the claim. …" and hints "The interest will then be calculated up until the claim is
          // settled or a judgment has been made." — the hint a shorter restatement, both present.
          //
          // Only emitted where the label came from somewhere else, because the chain above already
          // spends the hint as a label of last resort. A constant declaring nothing but a hint has that
          // hint standing in for its label, and writing it here as well would put one string in both
          // columns, which no definition does and which would silently change output for every such
          // constant already relying on the fallback.
          if (annotation != null && !isNullOrEmpty(annotation.hint())
              && !annotation.hint().equals(label)) {
            value.put("HintText", annotation.hint());
          }
          value.put("LiveFrom", JsonUtils.DEFAULT_LIVE_FROM);
          value.put("ID", listId);
          value.put("ListElementCode", enumConstant);
          value.put("DisplayOrder", order++);
        }

        Path path = Paths.get(dir.getPath(), listId + ".json");
        JsonUtils.mergeInto(path, fields, new AddMissing(), "ListElementCode");
      }
    }
  }
}
