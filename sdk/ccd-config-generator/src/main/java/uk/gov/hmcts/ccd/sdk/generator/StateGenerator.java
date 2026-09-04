package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.StateId;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

@Component
class StateGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  @SneakyThrows
  public void write(File root, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> result = Lists.newArrayList();
    int i = 1;
    if (config.getStateClass().isEnum()) {
      for (Object enumConstant : config.getStateClass().getEnumConstants()) {
        if (isIgnored(config.getStateClass(), enumConstant)) {
          continue;
        }
        Map<String, Object> field = enumToJsonMap(config.getCaseType(), config.getStateClass(), enumConstant,
            StateId.of(enumConstant));
        field.put("DisplayOrder", i++);
        result.add(field);
      }
    }

    Path output = Paths.get(root.getPath(), "State.json");
    JsonUtils.mergeInto(output, result, new JsonUtils.AddMissing(), "ID");
  }

  /**
   * Whether a state constant is excluded from the generated definition by
   * {@code @CCD(ignore = true)}.
   *
   * <p>A service reusing an existing {@code State} enum often has constants no case type declares —
   * a sentinel such as an {@code @JsonEnumDefaultValue UNKNOWN}, or a legacy composite state — which
   * cannot simply be deleted because the service's own code still switches on them. Without this,
   * every constant emits a {@code State} row and the definition gains states it never had.
   * {@code ignore = true} means the same thing here as it does on a case field: the member
   * contributes nothing to the definition. It also drops the constant's
   * {@code AuthorisationCaseState} rows, since a grant on a state that does not exist would fail to
   * import.
   *
   * <p>Read via {@link Enum#name()}, never {@code toString()}, for the reason given in
   * {@link uk.gov.hmcts.ccd.sdk.StateId}: an enum with an {@code @JsonValue toString()} returning
   * the lowercase id would otherwise throw {@code NoSuchFieldException}.
   *
   * @param enumType the state enum class
   * @param enumConstant the constant to test
   * @return true when the constant carries {@code @CCD(ignore = true)}
   */
  @SneakyThrows
  static boolean isIgnored(Class<?> enumType, Object enumConstant) {
    CCD ccd = enumType.getField(((Enum<?>) enumConstant).name()).getAnnotation(CCD.class);
    return ccd != null && ccd.ignore();
  }

  @SneakyThrows
  public static Map<String, Object> enumToJsonMap(String caseType, Class<?> enumType,
                                                  Object enumConstant, String id) {
    Map<String, Object> field = JsonUtils.caseRow(caseType);
    field.put("ID", id);

    // Read the constant's annotation via Enum.name(), not toString(): an enum whose toString() is
    // overridden (e.g. an @JsonValue toString() returning the lowercase id) would otherwise throw
    // NoSuchFieldException here.
    CCD ccd = enumType.getField(((Enum<?>) enumConstant).name()).getAnnotation(CCD.class);
    String name = ccd != null && !Strings.isNullOrEmpty(ccd.label()) ? ccd.label() :
        id;
    field.put("Name", name);
    // Description defaults to Name (today's behaviour); @CCD#description() overrides it.
    String description = ccd != null && !Strings.isNullOrEmpty(ccd.description()) ? ccd.description() : name;
    field.put("Description", description);
    String hint = ccd != null ? ccd.hint() : "";

    if (!Strings.isNullOrEmpty(hint)) {
      field.put("TitleDisplay", hint);
    }

    return field;
  }
}
