package uk.gov.hmcts.example.model;

import uk.gov.hmcts.example.enums.ClaimType;

/**
 * An interface whose DEFAULT method already serves a bean property, with no field behind it.
 *
 * <p>Models fpl's {@code TranslatableItem.getNeedTranslation()}: the definition declares a matching
 * {@code needsTranslation} CaseField row, which resolves to no model field, so retrofit would
 * synthesise one. Its Lombok getter would then have to override this method, and the definition's
 * FieldType has no reason to produce a compatible return type — on fpl it produced
 * "return type YesOrNo is not compatible with YesNo" on all seven implementors and failed the build.
 * The synthesis-collision guard must therefore treat an inherited accessor's property name as taken.
 */
public interface Translatable {

  ClaimType getClaimType();

  default String getNeedsTranslation() {
    return getClaimType() == null ? "No" : "Yes";
  }
}
