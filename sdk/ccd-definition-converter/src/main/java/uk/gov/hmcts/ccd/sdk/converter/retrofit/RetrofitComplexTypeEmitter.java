package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.palantir.javapoet.JavaFile;
import java.util.List;
import uk.gov.hmcts.ccd.sdk.converter.api.EmitContext;
import uk.gov.hmcts.ccd.sdk.converter.api.SourceEmitter;
import uk.gov.hmcts.ccd.sdk.converter.emit.model.ComplexTypeEmitter;
import uk.gov.hmcts.ccd.sdk.converter.model.CaseTypeModel;
import uk.gov.hmcts.ccd.sdk.converter.model.ComplexTypeModel;

/**
 * Retrofit companion emitter for the <em>definition-only</em> complex types: those the definition
 * declares but the team's model has no class for (e.g. Civil's SDO composites
 * {@code FastTrackEmployersLiability}, {@code SmallClaimsAllocation} — present only in the CCD
 * definition and manipulated elsewhere as raw map entries). A complex type that DOES resolve to an
 * existing top-level model class is annotated in place by the patch; generating a companion for it
 * would collide on the classpath, so it is filtered out here.
 *
 * <p>The actual class generation is delegated to the generate-mode {@link ComplexTypeEmitter}
 * (reuse, not fork): this wrapper simply narrows {@code model.getComplexTypes()} to the
 * definition-only subset before handing off, so the emitted {@code @ComplexType(generate = true)}
 * classes land in the model package exactly as generate mode's do — and the synthesised
 * definition-only field the patch adds (typed with that complex type's simple name) resolves against
 * them.
 */
public final class RetrofitComplexTypeEmitter implements SourceEmitter {

  private final ModelSourceIndex index;
  private final String modelPackage;
  private final ComplexTypeEmitter delegate = new ComplexTypeEmitter();

  /**
   * Creates the emitter.
   *
   * @param index the parsed model source index (to test whether a complex type already has a class)
   * @param modelPackage the team model package, preferred when a simple name is ambiguous
   */
  public RetrofitComplexTypeEmitter(ModelSourceIndex index, String modelPackage) {
    this.index = index;
    this.modelPackage = modelPackage;
  }

  @Override
  public List<JavaFile> emit(CaseTypeModel model, EmitContext context) {
    List<ComplexTypeModel> definitionOnly = model.getComplexTypes().stream()
        .filter(ct -> index.complexTypeClass(ct.getId(), modelPackage).isEmpty())
        .toList();
    CaseTypeModel filtered = model.toBuilder().complexTypes(definitionOnly).build();
    return delegate.emit(filtered, context);
  }
}
