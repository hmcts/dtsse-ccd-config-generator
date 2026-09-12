package uk.gov.hmcts.reform;

import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.reform.fpl.access.SolicitorAccess;

/**
 * Case data for {@link TypeParamReachCaseType}, pinning every polarity of
 * {@code @CCD(typeParameterClass)} at once.
 *
 * <p>Models the shape a retrofitted service takes: {@link #venueField} keeps its declared
 * {@code String} type — as the team's model really spells it, because nobody hand-writes 160-odd
 * venue codes as an enum — and names the list class instead of being retyped to it, so no caller or
 * serialised payload changes. Before {@code typeParameterClass} existed the field emitted a
 * {@code FieldTypeParameter} pointing at a list whose rows nothing generated.
 *
 * <p>{@link #ignoredVenueField} is the interaction with ignored fields: a field that is not part of
 * the definition must reach nothing, so naming a class from it cannot resurrect that class. The list
 * it names is deliberately {@link TypeParamReachUnreached}, which nothing else reaches — were the
 * ignore filter not applied first, that list would appear.
 */
@Data
public class TypeParamReachCaseData {

  @CCD(label = "A base field", access = {SolicitorAccess.class})
  private String baseField;

  @CCD(
      label = "Hearing Venue",
      typeOverride = FieldType.FixedList,
      typeParameterOverride = "FL_typeParamReachVenues",
      typeParameterClass = TypeParamReachVenue.class,
      access = {SolicitorAccess.class})
  private String venueField;

  @CCD(
      ignore = true,
      typeOverride = FieldType.FixedList,
      typeParameterOverride = "FL_typeParamReachUnreached",
      typeParameterClass = TypeParamReachUnreached.class)
  private String ignoredVenueField;
}
