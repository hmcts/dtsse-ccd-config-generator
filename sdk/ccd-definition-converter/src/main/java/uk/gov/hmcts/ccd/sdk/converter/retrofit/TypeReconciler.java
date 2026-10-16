package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import uk.gov.hmcts.ccd.sdk.converter.model.FieldModel;
import uk.gov.hmcts.ccd.sdk.type.FieldType;

/**
 * Reconciles a linked {@link FieldModel}'s declared CCD type against what the SDK would infer from
 * the TEAM's actual Java field type, adding the {@code typeOverride}/{@code typeParameterOverride}
 * the SDK needs to reproduce the definition's {@code FieldType}.
 *
 * <p>The linker chose overrides for a FRESH generated field of the definition's type, so its
 * {@code typeOverride} may not match the model's real Java type. When the model type mis-resolves and
 * the field carries no override yet:
 * <ul>
 *   <li>a concrete value-wrapper collection ({@code List<DocItem>}) forces {@code Collection} plus
 *       the definition's element as {@code typeParameterOverride} (proposal decision 8);</li>
 *   <li>any other conflict whose definition {@code FieldType} is a real SDK {@link FieldType}
 *       constant (e.g. model {@code String}=Text vs definition {@code Email}) forces that constant
 *       (plus its parameter).</li>
 * </ul>
 *
 * <p>A definition {@code FieldType} that is NOT a {@link FieldType} enum constant (e.g.
 * {@code Number}, {@code DateTime}, {@code AddressUK}, a custom complex type) cannot be expressed as
 * {@code @CCD(typeOverride = FieldType.X)} — the annotation would not compile. Such genuine
 * divergences are left un-annotated rather than emitting uncompilable code; they surface in the match
 * report as type conflicts for a maintainer to reconcile by hand on the model.
 *
 * <p>Applied identically to the root {@code CaseData} fields (via {@link RetrofitModelRebinder}) and
 * to every complex-type member (via {@link RetrofitPatchEmitter}), so a nested
 * {@code List<Wrapper>} member gets the same {@code typeParameterOverride} as a top-level one — the
 * asymmetry that dropped SSCS's nested {@code List<Correspondence>} overrides.
 */
final class TypeReconciler {

  private final TypeInference inference;

  TypeReconciler(ModelSourceIndex index) {
    this.inference = new TypeInference(index);
  }

  /**
   * Returns the field with any needed {@code typeOverride}/{@code typeParameterOverride} added,
   * reconciled against the resolved model property's real Java type; returns the field unchanged when
   * the model type already reproduces the definition type or it already carries an override.
   *
   * @param field the linked field model (its {@code fieldType}/{@code fieldTypeParameter} are the
   *              definition's declared type)
   * @param property the model property the matcher resolved this field to
   * @return the reconciled field
   */
  FieldModel reconcile(FieldModel field, ResolvedProperty property) {
    if (field.getTypeOverride() != null) {
      return field;
    }
    TypeInference.Inferred inferred = inference.infer(property);
    boolean compatible = TypeCompatibility.compatible(
        field.getFieldType(), field.getFieldTypeParameter(), inferred);
    if (compatible) {
      return field;
    }
    if (inferred.concreteWrapper) {
      return field.toBuilder()
          .typeOverride("Collection")
          .typeParameterOverride(field.getFieldTypeParameter())
          .build();
    }
    if (isFieldTypeConstant(field.getFieldType())) {
      FieldModel.FieldModelBuilder rebound = field.toBuilder().typeOverride(field.getFieldType());
      if (field.getTypeParameterOverride() == null && field.getFieldTypeParameter() != null) {
        rebound.typeParameterOverride(field.getFieldTypeParameter());
      }
      return rebound.build();
    }
    // A conflict whose definition FieldType is not a FieldType enum constant (a custom/unknown
    // complex type): inexpressible via @CCD(typeOverride=…). Leave it un-annotated — a wrong
    // FieldType.X would not compile — and let the match report flag it for a manual model
    // reconciliation. (DateTime/Number/AddressUK-family and the other completed constants now ARE
    // FieldType enum constants, so they take the typeOverride branch above.)
    return field;
  }

  private static final Set<String> FIELD_TYPE_CONSTANTS =
      Arrays.stream(FieldType.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

  static boolean isFieldTypeConstant(String fieldType) {
    return fieldType != null && FIELD_TYPE_CONSTANTS.contains(fieldType);
  }
}
