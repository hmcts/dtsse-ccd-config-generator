package uk.gov.hmcts.ccd.sdk.generator;

import static uk.gov.hmcts.ccd.sdk.FieldUtils.ccdAnnotation;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.getCaseFields;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.getFieldId;
import static uk.gov.hmcts.ccd.sdk.FieldUtils.isUnwrappedContainerId;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Label;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

@Component
class CaseFieldGenerator<T, S, R extends HasRole> implements ConfigGenerator<T, S, R> {

  // The field type set from code always takes precedence,
  // so eg. if a field changes type it gets updated.
  private static final ImmutableSet<String> OVERWRITES_FIELDS = ImmutableSet.of();

  @Override
  public void write(
      File outputFolder, ResolvedCCDConfig<T, S, R> config) {
    List<Map<String, Object>> fields = toComplex(config.getCaseClass(), config.getCaseType());

    Map<String, Object> history = getField(config.getCaseType(), "caseHistory");
    history.put("Label", " ");
    history.put("FieldType", "CaseHistoryViewer");
    fields.add(history);

    fields.addAll(getExplicitFields(config));

    Path path = Paths.get(outputFolder.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, fields, new JsonUtils.OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  public static List<Map<String, Object>> toComplex(Class dataClass, String caseTypeId) {
    return buildComplexFields(dataClass, caseTypeId);
  }

  private static <T, S, R extends HasRole> List<Map<String, Object>> getExplicitFields(
      ResolvedCCDConfig<T, S, R> config) {
    Map<String, uk.gov.hmcts.ccd.sdk.api.Field> explicitFields = Maps.newHashMap();
    for (Event event : config.getEvents().values()) {
      List<uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder> fc = event.getFields()
          .getExplicitFields();

      for (uk.gov.hmcts.ccd.sdk.api.Field.FieldBuilder fieldBuilder : fc) {
        uk.gov.hmcts.ccd.sdk.api.Field field = fieldBuilder.build();
        explicitFields.put(field.getId(), field);
      }
    }

    List<Map<String, Object>> result = Lists.newArrayList();
    for (String fieldId : explicitFields.keySet()) {
      // Don't export inbuilt metadata fields, nor an @JsonUnwrapped container's own name (which the
      // reflection walk emits no row for). Tested through isUnwrappedContainerId rather than name
      // alone so a real field whose CCD ID collides with a container's Java member name keeps its
      // row — see that method. Harmless here today only because such a field also arrives via the
      // reflection path; correct for the same reason regardless.
      if (fieldId.matches("\\[.+\\]") || isUnwrappedContainerId(config.getCaseClass(), fieldId)) {
        continue;
      }
      // A gated-off field placed on an event (e.g. a Label) must not emit its explicit CaseField
      // row either, mirroring the reflection filter that already drops gated-off CaseData members.
      if (config.getGatedOffFieldIds().contains(fieldId)) {
        continue;
      }

      final uk.gov.hmcts.ccd.sdk.api.Field field = explicitFields.get(fieldId);
      Map<String, Object> fieldData = getField(config.getCaseType(), fieldId);
      result.add(fieldData);

      Optional<Field> caseField = findCaseField(config.getCaseClass(), fieldId);
      caseField.ifPresent(candidate ->
          populateFieldMetadata(fieldData, config.getCaseClass(), candidate));
      JsonUtils.ensureDefaultLabel(fieldData);

      if (!Strings.isNullOrEmpty(field.getLabel())) {
        fieldData.put("Label", field.getLabel());
      }

      if (field.getType() != null) {
        fieldData.put("FieldType", field.getType());
      } else if (caseField.isEmpty()) {
        fieldData.put("FieldType", "Label");
      }

      if (field.getFieldTypeParameter() != null) {
        fieldData.put("FieldTypeParameter", field.getFieldTypeParameter());
      }
    }


    return result;
  }

  /**
   * Every {@code FieldTypeParameter} the generated definition references — across the case fields,
   * the members of every generated complex type, and the fields placed explicitly on events.
   *
   * <p>A {@code FixedLists} ID only means anything to CCD when some field's
   * {@code FieldTypeParameter} names it; a list nothing references is inert. Reflection over the
   * Java model reaches an enum whenever a field <em>declares</em> it, which is not the same thing: a
   * field may declare an enum and then override what it is, as sscs's
   * {@code @CCD(typeOverride = FieldType.Text) private DirectionType directionType} does (the
   * definition really does type that column {@code Text}), or carry the enum purely as an in-Java
   * value. Reachability alone therefore declares lists the definition does not have.
   *
   * <p>Deriving the live set from what each field declares itself to <em>be</em> keeps every list a
   * field genuinely references — sscs's {@code postponementEvent} is a real {@code FixedList} of
   * {@code eventType}, so that enum survives on the strength of that one field even though ten
   * others carry it as {@code Text} — and drops the rest. This is the converse of
   * {@code @CCD(typeParameterClass)}, which makes a list reachable that the Java type alone would
   * not reach.
   *
   * @param config the resolved configuration
   * @return the referenced type-parameter IDs, in walk order
   */
  static <T, S, R extends HasRole> Set<String> referencedTypeParameters(
      ResolvedCCDConfig<T, S, R> config) {
    Set<String> ids = new LinkedHashSet<>();
    collectTypeParameters(ids, toComplex(config.getCaseClass(), config.getCaseType()));
    for (Class<?> c : config.getTypes().keySet()) {
      // An enum has no members to reference anything — and walking one would be actively wrong,
      // since the resolver descends into an enum's own instance fields, so a constructor-carried
      // `private final Type type` makes Type reachable while the definition has no such list.
      // A @ComplexType(generate = false) type IS walked: it emits no ComplexTypes rows of its own
      // because the definition declares it elsewhere (hand-maintained or platform-predefined), but
      // it is still a type in the definition and its members still reference their lists — fpl's
      // StandardDirectionOrder.orderStatus is the only reference to the OrderStatus list.
      if (c.isEnum()) {
        continue;
      }
      collectTypeParameters(ids, toComplex(c, config.getCaseType()));
    }
    collectTypeParameters(ids, getExplicitFields(config));
    return ids;
  }

  private static void collectTypeParameters(Set<String> ids, List<Map<String, Object>> rows) {
    for (Map<String, Object> row : rows) {
      Object parameter = row.get("FieldTypeParameter");
      if (parameter != null && !Strings.isNullOrEmpty(parameter.toString())) {
        ids.add(parameter.toString());
      }
    }
  }

  private static List<Map<String, Object>> buildComplexFields(
      Class<?> dataClass, String caseTypeId) {
    List<Map<String, Object>> fields = Lists.newArrayList();
    appendFields(fields, dataClass, caseTypeId, "");
    return fields;
  }

  private static void appendFields(
      List<Map<String, Object>> fields,
      Class<?> dataClass,
      String caseTypeId,
      String idPrefix) {
    for (Field field : getCaseFields(dataClass)) {
      appendField(fields, caseTypeId, dataClass, field, idPrefix);
    }
  }

  private static void appendField(
      List<Map<String, Object>> fields,
      String caseTypeId,
      Class<?> ownerClass,
      Field field,
      String idPrefix) {
    JsonUnwrapped unwrapped = field.getAnnotation(JsonUnwrapped.class);
    if (unwrapped != null) {
      appendUnwrapped(fields, caseTypeId, field, idPrefix, unwrapped);
      return;
    }

    String id = getFieldId(field, idPrefix);
    Label label = field.getAnnotation(Label.class);
    JsonUtils.applyLabelAnnotation(fields, caseTypeId, label);

    Map<String, Object> fieldInfo = getField(caseTypeId, id);
    fields.add(fieldInfo);

    populateFieldMetadata(fieldInfo, ownerClass, field);
  }

  private static void appendUnwrapped(
      List<Map<String, Object>> fields,
      String caseTypeId,
      Field field,
      String currentPrefix,
      JsonUnwrapped unwrapped) {
    String prefix = currentPrefix.isEmpty()
        ? unwrapped.prefix()
        : currentPrefix.concat(StringUtils.capitalize(unwrapped.prefix()));
    appendFields(fields, field.getType(), caseTypeId, prefix);
  }

  private static void populateFieldMetadata(
      Map<String, Object> target, Class<?> ownerClass, Field field) {
    // Read through the owner, not off the field: an inherited member's configuration may be
    // overridden per subclass by a class-level @CCD(member) -- see CCD#member().
    CCD annotation = ccdAnnotation(ownerClass, field);
    JsonUtils.applyCcdAnnotation(target, annotation);
    JsonUtils.ensureDefaultLabel(target);

    if (annotation != null && annotation.typeOverride() != FieldType.Unspecified) {
      target.put("FieldType", annotation.typeOverride().toString());
      if (!Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
        target.put("FieldTypeParameter", annotation.typeParameterOverride());
      }
      return;
    }

    applyFieldType(ownerClass, field, target, annotation);
  }

  private static void applyFieldType(
      Class<?> dataClass, Field field, Map<String, Object> target, CCD annotation) {
    String resolvedType = resolveFieldType(dataClass, field, target, annotation);
    target.put("FieldType", resolvedType);
  }

  private static String resolveFieldType(
      Class<?> dataClass, Field field, Map<String, Object> target, CCD annotation) {
    String type = field.getType().getSimpleName();

    if (annotation != null && !Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
      target.put("FieldTypeParameter", annotation.typeParameterOverride());
    }

    if (Collection.class.isAssignableFrom(field.getType())) {
      type = resolveCollectionType(dataClass, field, target);
    } else {
      type = resolveSimpleType(field, target, type, annotation);
    }

    // For a complex-typed field, @ComplexType(name) overrides the FieldType with the CCD type ID.
    // An enum may now also carry @ComplexType(name) to preserve a renamed FixedList's list ID, but
    // there the name is the FieldTypeParameter (a FixedRadioList), NOT the FieldType — so exclude
    // enums, whose FieldType stays FixedList/FixedRadioList as resolveSimpleType decided.
    ComplexType complexType = field.getType().getAnnotation(ComplexType.class);
    if (complexType != null && !Strings.isNullOrEmpty(complexType.name())
        && !field.getType().isEnum()) {
      type = complexType.name();
    }

    return withNamedComplexType(field, target, type, annotation);
  }

  /**
   * The CCD type ID a class-valued {@code @CCD(typeParameterClass)} names, when the class carries a
   * {@code @ComplexType(name)} the declared type does not supply — else {@code inferredType}
   * unchanged.
   *
   * <p>{@code typeParameterClass} already makes such a class part of the definition (it is walked by
   * complex-type resolution exactly as a declared field type is, so it emits its {@code ComplexTypes}
   * rows). This is the other half: the field must also be TYPED as it, or the definition declares a
   * complex type nothing references while the field's column names the declared class's own ID
   * instead.
   *
   * <p>The case is a definition complex type whose members the team's class does not model, addressed
   * on a field the model already declares as something else — sscs's {@code jointPartyName} (three
   * members, its {@code title} a {@code FixedList}) addressed on a {@code Name} field, where
   * {@code Name} is separately the model class for the definition's own {@code name} type (four
   * members, {@code title} a {@code Text}). One class cannot carry both IDs, and
   * {@code typeOverride} cannot express either: it takes a {@link FieldType} constant, and a
   * definition type ID is not one. Naming the class here leaves the field's declared type — and hence
   * every caller and serialised payload — untouched, while the named class supplies both the rows and
   * this column's type ID.
   *
   * <p>An enum is excluded, as it is where {@code @ComplexType(name)} is read off the declared type:
   * for an enum the name is the list ID, i.e. the {@code FieldTypeParameter}, which the FixedList
   * branches already write. On a {@code Collection} field the named class is likewise the ELEMENT
   * type, so it supplies the {@code FieldTypeParameter} rather than the {@code FieldType}.
   */
  private static String withNamedComplexType(
      Field field, Map<String, Object> target, String inferredType, CCD annotation) {
    if (annotation == null || Void.class.equals(annotation.typeParameterClass())
        || annotation.typeParameterClass().isEnum()) {
      return inferredType;
    }
    ComplexType named = annotation.typeParameterClass().getAnnotation(ComplexType.class);
    if (named == null || Strings.isNullOrEmpty(named.name())) {
      return inferredType;
    }
    if (Collection.class.isAssignableFrom(field.getType())) {
      target.put("FieldTypeParameter", named.name());
      return inferredType;
    }
    return named.name();
  }

  private static String resolveCollectionType(
      Class<?> dataClass, Field field, Map<String, Object> target) {
    String type = "Collection";
    Class<?> elementClass = resolveCollectionElementType(dataClass, field);
    ComplexType complexType = elementClass.getAnnotation(ComplexType.class);
    if (complexType != null && !Strings.isNullOrEmpty(complexType.name())) {
      target.put("FieldTypeParameter", complexType.name());
    } else {
      target.put("FieldTypeParameter", elementClass.getSimpleName());
    }

    // Any collection of enum constants is a MultiSelectList, whatever the collection interface. The
    // distinction that matters to CCD is the wire shape, and Set and List of the same enum serialise
    // identically — Jackson writes ["CODE_A","CODE_B"] for both. What produces the Collection shape,
    // [{"id":…,"value":…}], is the ListValue<T> wrapper, which neither has.
    //
    // Gating on Set therefore did not select a legitimate alternative typing for List<Enum>; it
    // produced a definition that cannot be imported. Collection carrying a FixedLists parameter
    // resolves to the type reference Collection-<list>, which no base type declares, so the import
    // fails outright. FPL shows the cost on a live service: its Orders class declares
    // List<OrderType> orderType beside two siblings that carry typeOverride = MultiSelectList
    // explicitly, its own show conditions read orderType CONTAINS …, and its hand-written definition
    // says MultiSelectList — while the generator emitted Collection, the hand-written file masking it.
    if (elementClass.isEnum()) {
      type = "MultiSelectList";
    }
    return type;
  }

  private static String resolveSimpleType(
      Field field,
      Map<String, Object> target,
      String inferredType,
      CCD annotation) {
    ComplexType complexType = field.getType().getAnnotation(ComplexType.class);
    if (field.getType().isEnum() && (complexType == null || complexType.generate())) {
      // The list ID a FixedRadioList field references is the enum's @ComplexType(name) when set
      // (a PascalCase-renamed enum preserving its original CCD list ID), else the simple name.
      String listId = complexType != null && !Strings.isNullOrEmpty(complexType.name())
          ? complexType.name() : field.getType().getSimpleName();
      target.putIfAbsent("FieldTypeParameter", listId);
      return "FixedRadioList";
    }
    return switch (inferredType) {
      case "String" -> {
        if (annotation != null && !Strings.isNullOrEmpty(annotation.typeParameterOverride())) {
          yield "FixedList";
        }
        yield "Text";
      }
      case "LocalDate" -> "Date";
      case "LocalDateTime" -> "DateTime";
      // BigDecimal belongs here as much as the primitives do — it is the type a service reaches for
      // when a Number field holds money, and the converter's own TypeInference has always counted it
      // among the numeric types. Omitting it here did not fall back to something merely imprecise: the
      // switch's default yields the inferred type verbatim, so the field emitted FieldType=BigDecimal,
      // a name the definition store's base types do not contain, and the whole import failed with
      // "Missing field type". Civil declares six of them (totalClaimAmount, totalInterest and the
      // defaultJudgementOverallTotal family) against a definition that asks for Number.
      case "int", "float", "double", "Integer", "Float", "Double", "Long", "long",
           "BigDecimal" -> "Number";
      default -> inferredType;
    };
  }

  private static Class<?> resolveCollectionElementType(Class<?> dataClass, Field field) {
    ResolvableType fieldType = ResolvableType.forField(field, dataClass);
    ResolvableType elementType = fieldType.getGeneric(0);
    if (elementType.hasGenerics()) {
      elementType = elementType.getGeneric(0);
    }
    Class<?> resolved = elementType.resolve();
    if (resolved == null) {
      throw new IllegalStateException("Unable to resolve element type for %s on %s"
          .formatted(field.getName(), dataClass.getName()));
    }
    return resolved;
  }

  public static Map<String, Object> getField(String caseType, String id) {
    Map<String, Object> result = JsonUtils.caseRow(caseType);
    result.put("ID", id);
    result.put("SecurityClassification", "Public");
    return result;
  }

  private static Optional<Field> findCaseField(Class<?> caseClass, String fieldId) {
    return getCaseFields(caseClass)
        .stream()
        .filter(candidate -> getFieldId(candidate).equals(fieldId))
        .findFirst();
  }

}
