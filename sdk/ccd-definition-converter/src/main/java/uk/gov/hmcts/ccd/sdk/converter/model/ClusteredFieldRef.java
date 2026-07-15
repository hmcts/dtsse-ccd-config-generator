package uk.gov.hmcts.ccd.sdk.converter.model;

import lombok.Builder;
import lombok.Value;

/**
 * How to reference a flat CCD field that has been folded into a {@code @JsonUnwrapped} cluster.
 *
 * <p>Instead of {@code CaseData::getApplicant1FirstName}, the config emitter must descend into
 * the unwrapped complex parent and reference the member getter, e.g.
 * {@code .complex(CaseData::getApplicant1).optional(Applicant::getFirstName)}. The SDK flattens
 * that back to the original {@code applicant1FirstName} CaseEventToFields row.
 *
 * <p>When a model nests {@code @JsonUnwrapped} members (retrofit mode only — generate mode's
 * clusterer produces a single level), the leaf is reached through more than one {@code .complex()}
 * hop; {@link #parentHops} carries the intermediate hops after the first ({@link #parentGetter} /
 * {@link #clusterType}), so the emitter can open a nested {@code .complex(A::getB).complex(B::getC)
 * …x(C::getLeaf)} chain. It is empty for the ordinary single-level case.
 */
@Value
@Builder
public class ClusteredFieldRef {

  /**
   * Getter name on CaseData for the (outermost) unwrapped parent field, e.g. {@code getApplicant1}.
   */
  String parentGetter;

  /**
   * Simple name of the (outermost) cluster complex type, e.g. {@code Applicant}.
   */
  String clusterType;

  /**
   * Getter name on the (innermost) cluster type for the member, e.g. {@code getFirstName}.
   */
  String memberGetter;

  /**
   * Package the {@link #clusterType} lives in. Null in generate mode (the cluster type is a freshly
   * generated class in the model package); set in retrofit mode when the team's
   * {@code @JsonUnwrapped} parent type lives in a different package from the root model class.
   */
  String clusterTypePackage;

  /**
   * Intermediate {@code .complex()} hops between the outermost parent and the leaf's declaring type,
   * for a nested {@code @JsonUnwrapped} chain (retrofit mode). Each hop's {@link Hop#getGetter()} is
   * invoked on the previous hop's type ({@link #clusterType} for the first). Empty for a
   * single-level cluster; the leaf getter ({@link #memberGetter}) is then invoked on the last hop's
   * type, or on {@link #clusterType} when there are no hops.
   */
  @Builder.Default
  java.util.List<Hop> parentHops = java.util.List.of();

  /**
   * One nested {@code .complex()} hop: the getter to invoke and the type it returns.
   */
  @Value
  @Builder
  public static class Hop {
    /**
     * Getter name invoked on the enclosing type, e.g. {@code getMediationSuccessful}.
     */
    String getter;
    /**
     * Simple name of the type the getter returns, e.g. {@code MediationSuccessful}.
     */
    String type;
    /**
     * Package the {@link #type} lives in.
     */
    String typePackage;
  }
}
