package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import uk.gov.hmcts.ccd.sdk.converter.ir.Columns;
import uk.gov.hmcts.ccd.sdk.converter.ir.DefinitionIr;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetName;
import uk.gov.hmcts.ccd.sdk.converter.ir.SheetRow;
import uk.gov.hmcts.ccd.sdk.converter.retrofit.ModelSourceIndex.Type;

/**
 * The phase-1 retrofit matcher: resolves every data-bearing {@code CaseField} ID in a definition
 * against a team's existing Java model using the SDK's own Jackson-faithful resolution
 * ({@link PropertyResolver}), classifies each into the taxonomy
 * (EXACT_MATCH / TYPE_CONFLICT / UNMATCHED_DEFINITION_FIELD), lists the reverse UNMATCHED_JAVA_FIELD
 * set, and reports the state-enum verdict, collection-wrapper survey and annotation counts. It
 * mutates no source and emits no patch — that is phase 2.
 */
public final class RetrofitMatcher {

  /** Below this many resolvable properties for a large definition, the model is treated as map-based. */
  private static final int MAP_BASED_PROPERTY_FLOOR = 5;
  private static final int MAP_BASED_DEFINITION_FLOOR = 100;

  private final DefinitionIr ir;
  private final String caseTypeId;
  private final Path modelSourceRoot;
  private final String modelPackage;
  private final String modelClassSimpleName;

  // Retained after match() so phase 2 (patch + companion sources) reuses the single source parse and
  // property resolution rather than re-parsing the (large) model tree.
  private ModelSourceIndex index;
  private PropertyResolver.Resolution resolution;
  private ModelSourceIndex.Type root;

  /**
   * Creates a matcher.
   *
   * @param ir the parsed definition
   * @param caseTypeId the case type to match
   * @param modelSourceRoot the {@code src/main/java} root of the team's model
   * @param modelPackage the model package (e.g. {@code uk.gov.hmcts.reform.fpl.model})
   * @param modelClassSimpleName the root model class simple name (e.g. {@code CaseData}), or null
   *                             to default to {@code CaseData}
   */
  public RetrofitMatcher(DefinitionIr ir, String caseTypeId, Path modelSourceRoot,
      String modelPackage, String modelClassSimpleName) {
    this.ir = ir;
    this.caseTypeId = caseTypeId;
    this.modelSourceRoot = modelSourceRoot;
    this.modelPackage = modelPackage;
    this.modelClassSimpleName = modelClassSimpleName == null ? "CaseData" : modelClassSimpleName;
  }

  /**
   * Runs the match and produces the report.
   *
   * @return the retrofit report
   */
  public RetrofitReport match() {
    this.index = ModelSourceIndex.parse(modelSourceRoot);
    Optional<Type> root = locateRoot(index);
    this.root = root.orElse(null);

    DefinitionFields definition = readDefinitionFields();
    Set<String> definitionStateIds = readDefinitionStateIds();

    RetrofitReport.RetrofitReportBuilder report = RetrofitReport.builder()
        .caseTypeId(caseTypeId)
        .modelClass(modelPackage + "." + modelClassSimpleName)
        .modelPackage(modelPackage)
        .modelSourceRoot(modelSourceRoot.toString())
        .totalDefinitionFields(definition.totalIds)
        .labelFields(definition.labelIds.size())
        .dataBearingFields(definition.dataBearing.size());

    if (root.isEmpty()) {
      return report
          .mapBased(true)
          .notApplicableReason("Model class " + modelPackage + "." + modelClassSimpleName
              + " was not found under " + modelSourceRoot
              + "; retrofit not applicable — use generate mode.")
          .stateVerdict(new StateEnumAnalyser(index).analyse(modelPackage, definitionStateIds))
          .build();
    }

    this.resolution = new PropertyResolver(index).resolve(root.get());
    Map<String, ResolvedProperty> properties = resolution.properties;
    aliasFirstLetterCaseVariants(definition, properties);

    // Map-based detection (IA): a HashMap/Map subclass, or a model that resolves to almost nothing
    // against a large definition, cannot be annotated field-by-field (proposal decision 6).
    String mapReason = mapBasedReason(root.get(), properties.size(), definition.dataBearing.size());
    report
        .resolvableModelProperties(properties.size())
        .jsonUnwrappedCount(resolution.unwrappedCount)
        .prefixlessJsonUnwrappedCount(resolution.prefixlessUnwrappedCount)
        .jsonIgnoreCount(resolution.ignoredCount)
        .superclassCount(resolution.superclassCount)
        .stateVerdict(new StateEnumAnalyser(index).analyse(modelPackage, definitionStateIds));
    if (mapReason != null) {
      return report.mapBased(true).notApplicableReason(mapReason).build();
    }

    TypeInference inference = new TypeInference(index);
    classifyFields(definition, properties, inference, report);
    classifyUnmatchedJavaFields(definition, properties, report);
    report.collectionSurvey(surveyCollections(properties, inference));

    return report.build();
  }

  /**
   * The parsed model-source index, available after {@link #match()} so phase 2 reuses the single
   * parse. Null before {@code match()} runs.
   *
   * @return the model source index
   */
  ModelSourceIndex index() {
    return index;
  }

  /**
   * The property resolution (CCD id → model property), available after {@link #match()}. Null when
   * the model was map-based / the root class was not found.
   *
   * @return the resolution, or null
   */
  PropertyResolver.Resolution resolution() {
    return resolution;
  }

  /**
   * The resolved root model type, available after {@link #match()}. Null when not found.
   *
   * @return the root type, or null
   */
  Type root() {
    return root;
  }

  /**
   * Binds a definition ID that differs from a resolved property's CCD id only by the case of its
   * first letter (e.g. the definition's PascalCase {@code SmallClaimHearingInterpreterRequired}
   * against the model's camelCase field {@code smallClaimHearingInterpreterRequired}). Jackson would
   * serialise the field under its camelCase name, so the SDK's default id does not match the
   * definition's; without this the field is classified UNMATCHED_JAVA (→ {@code @CCD(ignore=true)})
   * <em>and</em> the definition id is classified UNMATCHED_DEFINITION (→ a synthesised field whose
   * decapitalised javaName collides with the existing field, a compile error hit on Civil's 17
   * PascalCase ids). Aliasing the existing property under the definition's id instead makes it a
   * normal match; the patch emitter then adds the {@code @JsonProperty("<PascalCaseId>")} that binds
   * the field to the definition id (its {@code renameFor} fires because member != id). Only applied
   * when the exact id is genuinely absent and exactly one first-letter-case variant resolves, so it
   * never overrides a real match or guesses among ambiguous candidates.
   */
  private void aliasFirstLetterCaseVariants(
      DefinitionFields definition, Map<String, ResolvedProperty> properties) {
    for (DefinitionField field : definition.dataBearing) {
      if (properties.containsKey(field.id) || field.id.isEmpty()) {
        continue;
      }
      String variant = swapFirstLetterCase(field.id);
      ResolvedProperty property = properties.get(variant);
      // Only alias when the variant resolves to a directly-declared member (unwrapped leaves compose
      // their id from a prefix, so a case swap there is not a simple @JsonProperty rename) and the
      // definition does not itself already declare the camelCase id as a separate field.
      if (property != null && property.unwrap == null && !definitionDeclares(definition, variant)) {
        properties.put(field.id, property);
      }
    }
  }

  private boolean definitionDeclares(DefinitionFields definition, String id) {
    for (DefinitionField field : definition.dataBearing) {
      if (field.id.equals(id)) {
        return true;
      }
    }
    return definition.labelIds.contains(id);
  }

  private static String swapFirstLetterCase(String id) {
    char first = id.charAt(0);
    char swapped = Character.isUpperCase(first) ? Character.toLowerCase(first)
        : Character.toUpperCase(first);
    return swapped + id.substring(1);
  }

  private void classifyFields(DefinitionFields definition, Map<String, ResolvedProperty> properties,
      TypeInference inference, RetrofitReport.RetrofitReportBuilder report) {
    int exact = 0;
    int conflict = 0;
    int unmatched = 0;
    for (DefinitionField field : definition.dataBearing) {
      ResolvedProperty property = properties.get(field.id);
      if (property == null) {
        unmatched++;
        report.field(RetrofitReport.FieldFinding.builder()
            .id(field.id)
            .bucket(RetrofitReport.Bucket.UNMATCHED_DEFINITION_FIELD)
            .definitionFieldType(field.fieldType)
            .definitionFieldTypeParameter(field.fieldTypeParameter)
            .modelClass(modelClassSimpleName)
            .action("synthesise typed @CCD field on " + modelClassSimpleName)
            .build());
        continue;
      }
      TypeInference.Inferred inferred = inference.infer(property);
      boolean compatible = TypeCompatibility.compatible(
          field.fieldType, field.fieldTypeParameter, inferred);
      if (compatible) {
        exact++;
        report.field(RetrofitReport.FieldFinding.builder()
            .id(field.id)
            .bucket(RetrofitReport.Bucket.EXACT_MATCH)
            .definitionFieldType(field.fieldType)
            .definitionFieldTypeParameter(field.fieldTypeParameter)
            .modelClass(property.ownerSimpleName)
            .memberName(property.memberName)
            .inferredFieldType(inferred.fieldType)
            .action("annotate only (@CCD for label/hint/access)")
            .build());
      } else {
        conflict++;
        report.field(RetrofitReport.FieldFinding.builder()
            .id(field.id)
            .bucket(RetrofitReport.Bucket.TYPE_CONFLICT)
            .definitionFieldType(field.fieldType)
            .definitionFieldTypeParameter(field.fieldTypeParameter)
            .modelClass(property.ownerSimpleName)
            .memberName(property.memberName)
            .inferredFieldType(inferred.fieldType)
            .concreteWrapper(inferred.concreteWrapper)
            .action(conflictAction(field, inferred))
            .build());
      }
    }
    report.exactMatches(exact).typeConflicts(conflict).unmatchedDefinitionFields(unmatched);
  }

  /**
   * The recommended remediation for a type conflict. A concrete value-wrapper collection gets a
   * {@code typeParameterOverride}; a conflict whose definition {@code FieldType} is a real SDK
   * {@link uk.gov.hmcts.ccd.sdk.type.FieldType} constant gets a {@code typeOverride}. A conflict
   * whose {@code FieldType} is NOT a FieldType constant (e.g. {@code Number}, {@code DateTime},
   * {@code AddressUK}, a custom complex type) cannot be expressed as {@code @CCD(typeOverride=…)} —
   * the annotation would not compile — so the report says so honestly rather than recommending
   * uncompilable code (the misleading recommendation behind finding A1: the phase-2 emitter correctly
   * drops these, but phase-1 was still suggesting them). Such fields are a genuine model-vs-definition
   * type divergence a maintainer must reconcile on the model itself.
   */
  private static String conflictAction(DefinitionField field, TypeInference.Inferred inferred) {
    if (inferred.concreteWrapper) {
      return "@CCD(typeParameterOverride = \"" + field.fieldTypeParameter + "\")";
    }
    if (TypeReconciler.isFieldTypeConstant(field.fieldType)) {
      return "@CCD(typeOverride = FieldType." + field.fieldType + ")";
    }
    return "genuine type divergence: definition FieldType '" + field.fieldType + "' is not an SDK "
        + "FieldType constant and cannot be expressed via @CCD(typeOverride) over the model's "
        + inferred.fieldType + " field; reconcile the model type by hand";
  }

  private void classifyUnmatchedJavaFields(DefinitionFields definition,
      Map<String, ResolvedProperty> properties, RetrofitReport.RetrofitReportBuilder report) {
    Set<String> definitionIds = new LinkedHashSet<>();
    definition.dataBearing.forEach(f -> definitionIds.add(f.id));
    definitionIds.addAll(definition.labelIds);
    for (ResolvedProperty property : properties.values()) {
      if (!definitionIds.contains(property.ccdId)) {
        report.unmatchedJavaField(RetrofitReport.UnmatchedJavaField.builder()
            .id(property.ccdId)
            .modelClass(property.ownerSimpleName)
            .memberName(property.memberName)
            .action("@CCD(ignore = true) (or already @JsonIgnore)")
            .build());
      }
    }
  }

  private RetrofitReport.CollectionSurvey surveyCollections(
      Map<String, ResolvedProperty> properties, TypeInference inference) {
    int total = 0;
    int generic = 0;
    int concrete = 0;
    Set<String> concreteTypes = new TreeSet<>();
    for (ResolvedProperty property : properties.values()) {
      TypeInference.Inferred inferred = inference.infer(property);
      if (!inferred.collection) {
        continue;
      }
      total++;
      if (inferred.concreteWrapper) {
        concrete++;
        if (inferred.fieldTypeParameter != null) {
          concreteTypes.add(inferred.fieldTypeParameter);
        }
      } else {
        generic++;
      }
    }
    RetrofitReport.CollectionSurvey.CollectionSurveyBuilder builder =
        RetrofitReport.CollectionSurvey.builder()
            .totalCollectionFields(total)
            .genericWrapperFields(generic)
            .concreteWrapperFields(concrete);
    concreteTypes.forEach(builder::concreteWrapperType);
    return builder.build();
  }

  private String mapBasedReason(Type root, int propertyCount, int dataBearingCount) {
    if (extendsMap(root)) {
      return "Model class " + root.simpleName + " extends a Map/HashMap and carries no domain "
          + "fields to annotate; retrofit not applicable — use generate mode.";
    }
    if (propertyCount < MAP_BASED_PROPERTY_FLOOR && dataBearingCount > MAP_BASED_DEFINITION_FLOOR) {
      return "Model class " + root.simpleName + " resolves to only " + propertyCount
          + " properties for " + dataBearingCount + " data-bearing definition fields; it is not a "
          + "field-per-CaseField model. Retrofit not applicable — use generate mode.";
    }
    return null;
  }

  private boolean extendsMap(Type type) {
    if (!type.decl.isClassOrInterfaceDeclaration()) {
      return false;
    }
    return type.decl.asClassOrInterfaceDeclaration().getExtendedTypes().stream()
        .map(e -> e.getNameAsString())
        .anyMatch(name -> name.equals("HashMap") || name.equals("Map")
            || name.equals("LinkedHashMap") || name.equals("TreeMap")
            || name.equals("ConcurrentHashMap"));
  }

  private Optional<Type> locateRoot(ModelSourceIndex index) {
    Optional<Type> byFqn = index.byFqn(modelPackage + "." + modelClassSimpleName);
    if (byFqn.isPresent()) {
      return byFqn;
    }
    return index.bySimpleName(modelClassSimpleName, modelPackage);
  }

  private DefinitionFields readDefinitionFields() {
    Set<String> seen = new LinkedHashSet<>();
    List<DefinitionField> dataBearing = new java.util.ArrayList<>();
    Set<String> labels = new LinkedHashSet<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.CASE_FIELD, caseTypeId)) {
      String id = row.getString(Columns.ID).orElse(null);
      if (id == null || !seen.add(id)) {
        continue;
      }
      String fieldType = row.getString(Columns.FIELD_TYPE).orElse("Text");
      if ("Label".equals(fieldType)) {
        labels.add(id);
        continue;
      }
      dataBearing.add(new DefinitionField(id, fieldType,
          row.getString(Columns.FIELD_TYPE_PARAMETER).orElse(null)));
    }
    DefinitionFields fields = new DefinitionFields();
    fields.totalIds = seen.size();
    fields.labelIds = labels;
    fields.dataBearing = dataBearing;
    return fields;
  }

  private Set<String> readDefinitionStateIds() {
    Set<String> ids = new LinkedHashSet<>();
    for (SheetRow row : ir.rowsForCaseType(SheetName.STATE, caseTypeId)) {
      row.getString(Columns.ID).ifPresent(ids::add);
    }
    return ids;
  }

  /** A data-bearing definition CaseField: its ID and declared type columns. */
  private static final class DefinitionField {
    final String id;
    final String fieldType;
    final String fieldTypeParameter;

    DefinitionField(String id, String fieldType, String fieldTypeParameter) {
      this.id = id;
      this.fieldType = fieldType;
      this.fieldTypeParameter = fieldTypeParameter;
    }
  }

  /** The definition's CaseField IDs partitioned into labels and data-bearing fields. */
  private static final class DefinitionFields {
    int totalIds;
    Set<String> labelIds = new LinkedHashSet<>();
    List<DefinitionField> dataBearing = List.of();
  }
}
