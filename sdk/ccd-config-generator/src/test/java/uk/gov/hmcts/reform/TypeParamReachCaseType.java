package uk.gov.hmcts.reform;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.TypeParamReachState.Open;
import static uk.gov.hmcts.reform.TypeParamReachState.Submitted;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.LOCAL_AUTHORITY;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.reform.fpl.enums.UserRole;

/**
 * A case type whose fixed list is named by {@code @CCD(typeParameterClass)} rather than declared as a
 * field type. The snapshot pins that the named list emits its {@code FixedLists} rows under the ID its
 * {@code @ComplexType(name)} carries, while a list named only from an ignored field — and one named
 * from nowhere — emits nothing.
 */
@Component
public class TypeParamReachCaseType
    implements CCDConfig<TypeParamReachCaseData, TypeParamReachState, UserRole> {

  @Override
  public void configure(
      ConfigBuilder<TypeParamReachCaseData, TypeParamReachState, UserRole> builder) {
    builder.caseType("TypeParamReach", "Type parameter reach", "typeParameterClass case type");

    builder.event("create")
        .forStateTransition(Open, Submitted)
        .name("Create")
        .grant(CRU, LOCAL_AUTHORITY)
        .fields()
        .optional(TypeParamReachCaseData::getBaseField)
        .optional(TypeParamReachCaseData::getVenueField);
  }
}
