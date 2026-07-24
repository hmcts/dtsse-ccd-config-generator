package uk.gov.hmcts.ccd.sdk.converter.retrofit;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.type.Type;
import java.nio.file.Path;

/**
 * One Java model property the resolver mapped a CCD field ID to: its composed CCD ID, the class it
 * was declared on, its Java member name and its declared type (kept as a JavaParser node so the
 * type-compatibility check can inspect generics). Mirrors what the SDK would reflect for this field
 * at generation time.
 *
 * <p>When the property was reached by descending a top-level {@code @JsonUnwrapped} member of the
 * root model class, {@link #unwrap} records how the SDK config must reference it — a
 * {@code .complex(CaseData::getParent).x(ParentType::getMember)} clustered leaf rather than a flat
 * {@code CaseData::getMember} getter (phase-2 config emission mirrors generate mode's clustered
 * leaves). Null for a directly-declared (possibly inherited) member.
 */
final class ResolvedProperty {

  /**
   * The composed CCD field ID (field name / {@code @JsonProperty} / unwrapped prefix path).
   */
  final String ccdId;

  /**
   * Simple name of the class declaring the field (the {@code @JsonUnwrapped} leaf owner).
   */
  final String ownerSimpleName;

  /**
   * The Java field name (before any {@code @JsonProperty} rename).
   */
  final String memberName;

  /** The field's declared Java type. */
  final Type declaredType;

  /**
   * The compilation unit the field was declared in, for resolving {@link #declaredType}.
   */
  final CompilationUnit context;

  /**
   * How to reference the property through the SDK config when it was reached via a top-level
   * {@code @JsonUnwrapped} member of the root class; null for a direct member.
   */
  final UnwrapRef unwrap;

  /**
   * The {@code .java} file the owning class was declared in (for the phase-2 patch emitter).
   */
  final Path ownerFile;

  ResolvedProperty(String ccdId, String ownerSimpleName, String memberName, Type declaredType,
      CompilationUnit context, UnwrapRef unwrap, Path ownerFile) {
    this.ccdId = ccdId;
    this.ownerSimpleName = ownerSimpleName;
    this.memberName = memberName;
    this.declaredType = declaredType;
    this.context = context;
    this.unwrap = unwrap;
    this.ownerFile = ownerFile;
  }

  /**
   * The {@code @JsonUnwrapped} parent chain through which an unwrapped leaf was reached: one
   * {@link Hop} per unwrap step, ordered outermost-first (the first hop's member is declared on the
   * root class). The SDK config places the leaf as a nested {@code .complex(CaseData::getHop0)
   * .complex(Hop0Type::getHop1)…x(LeafType::getMember)} chain, each {@code @JsonUnwrapped(prefix)}
   * reconstituting the composed CCD ID exactly as generate mode does. A single-element chain is the
   * common (single-level) case; a multi-element chain arises when a model nests {@code @JsonUnwrapped}
   * members (e.g. Civil's {@code CaseData.mediation → Mediation.mediationSuccessful}), which the
   * previous single-parent ref could not express (it emitted the leaf getter on the outer parent
   * type, which does not declare it — a compile error).
   */
  static final class UnwrapRef {
    /** The unwrap hops, outermost-first. Never empty. */
    final java.util.List<Hop> hops;

    UnwrapRef(java.util.List<Hop> hops) {
      this.hops = java.util.List.copyOf(hops);
    }

    /** Extends this chain with one more inner unwrap hop, returning a new ref. */
    UnwrapRef plus(Hop hop) {
      java.util.List<Hop> extended = new java.util.ArrayList<>(hops);
      extended.add(hop);
      return new UnwrapRef(extended);
    }
  }

  /**
   * One {@code @JsonUnwrapped} step: the member declared on the enclosing type and the unwrapped
   * type it points at (its simple name and package, for the config emitter's ClassName).
   */
  static final class Hop {
    /**
     * The unwrapped member name on the enclosing type (e.g. {@code mediationSuccessful}).
     */
    final String memberName;
    /**
     * The unwrapped type's simple name (e.g. {@code MediationSuccessful}).
     */
    final String typeSimpleName;
    /**
     * The unwrapped type's package.
     */
    final String typePackage;

    Hop(String memberName, String typeSimpleName, String typePackage) {
      this.memberName = memberName;
      this.typeSimpleName = typeSimpleName;
      this.typePackage = typePackage;
    }
  }
}
