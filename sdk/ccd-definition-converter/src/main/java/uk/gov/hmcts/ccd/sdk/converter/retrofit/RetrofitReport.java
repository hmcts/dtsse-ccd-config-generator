package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * The complete result of a phase-1 retrofit match: how every data-bearing definition
 * {@code CaseField} ID resolved against the team's existing Java model, plus the reverse list of
 * unmatched model fields, the state-enum verdict, the collection-wrapper survey, and the annotation
 * counts. Serialised to {@code retrofit-report.json} and rendered to {@code retrofit-report.md}.
 */
@Value
@Builder
public class RetrofitReport {

  /** The exactly-one bucket each definition field lands in. */
  public enum Bucket {
    /** ID resolves to a model property whose type infers to the definition's FieldType. */
    EXACT_MATCH,
    /** ID resolves but the model type infers to a different FieldType. */
    TYPE_CONFLICT,
    /** No model property resolves for this definition field ID. */
    UNMATCHED_DEFINITION_FIELD
  }

  String caseTypeId;
  String modelClass;
  String modelPackage;
  String modelSourceRoot;

  /**
   * True when the model is map-based (a Map/HashMap subclass or too few resolvable properties).
   */
  boolean mapBased;
  /**
   * Human-readable reason retrofit is not applicable, when {@link #mapBased}.
   */
  String notApplicableReason;

  /** Total distinct CaseField IDs in the definition (data + labels). */
  int totalDefinitionFields;
  /**
   * Definition fields with {@code FieldType: Label} — excluded from the denominator.
   */
  int labelFields;
  /** Data-bearing definition fields (the denominator). */
  int dataBearingFields;

  int exactMatches;
  int typeConflicts;
  int unmatchedDefinitionFields;

  /** Distinct emittable model property IDs the resolver derived from the model. */
  int resolvableModelProperties;

  /** Per-field classifications, one per data-bearing definition field. */
  @Singular
  List<FieldFinding> fields;

  /**
   * Model properties with no definition ID (candidates for {@code @CCD(ignore=true)}).
   */
  @Singular
  List<UnmatchedJavaField> unmatchedJavaFields;

  StateVerdict stateVerdict;
  CollectionSurvey collectionSurvey;

  /**
   * {@code @JsonUnwrapped} sub-objects walked while resolving the model.
   */
  int jsonUnwrappedCount;
  /**
   * Prefix-less unwraps among {@link #jsonUnwrappedCount}.
   */
  int prefixlessJsonUnwrappedCount;
  /**
   * Fields excluded by {@code @JsonIgnore} / {@code @CCD(ignore=true)}.
   */
  int jsonIgnoreCount;
  /** Superclasses walked above the root model class. */
  int superclassCount;

  /** The resolved rate (exact + type-conflict) as a percentage of data-bearing fields. */
  public double resolvedPercent() {
    return dataBearingFields == 0 ? 0
        : 100.0 * (exactMatches + typeConflicts) / dataBearingFields;
  }

  /** The exact-match rate as a percentage of data-bearing fields. */
  public double exactPercent() {
    return dataBearingFields == 0 ? 0 : 100.0 * exactMatches / dataBearingFields;
  }

  /** One classified definition field. */
  @Value
  @Builder
  public static class FieldFinding {
    String id;
    Bucket bucket;
    String definitionFieldType;
    String definitionFieldTypeParameter;
    /** The model class the ID resolved to, or the best-guess synth target when unmatched. */
    String modelClass;
    /** The Java member the ID resolved to, or null when unmatched. */
    String memberName;
    /** The inferred CCD FieldType of the model member, or null when unmatched. */
    String inferredFieldType;
    /**
     * True for a TYPE_CONFLICT caused by a concrete collection wrapper (decision 8).
     */
    boolean concreteWrapper;
    /** The recommended retrofit action for this field. */
    String action;
  }

  /** A model property with no matching definition field. */
  @Value
  @Builder
  public static class UnmatchedJavaField {
    String id;
    String modelClass;
    String memberName;
    String action;
  }

  /** The state-enum reconciliation verdict. */
  @Value
  @Builder
  public static class StateVerdict {
    /**
     * True when a candidate {@code State} enum was found in the model source.
     */
    boolean stateEnumFound;
    String stateEnumClass;
    /**
     * True when the enum overrides {@code toString()} (e.g. an {@code @JsonValue} id).
     */
    boolean toStringOverridden;
    int definitionStates;
    int matchedStates;
    int conflictingStates;
    @Singular("conflict")
    List<String> conflicts;
    String summary;
  }

  /** The collection-wrapper survey: generic wrappers vs concrete value-bearing wrappers. */
  @Value
  @Builder
  public static class CollectionSurvey {
    int totalCollectionFields;
    int genericWrapperFields;
    int concreteWrapperFields;
    @Singular("concreteWrapperType")
    List<String> concreteWrapperTypes;
  }
}
