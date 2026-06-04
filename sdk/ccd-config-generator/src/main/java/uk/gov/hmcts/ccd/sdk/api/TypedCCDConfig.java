package uk.gov.hmcts.ccd.sdk.api;

public interface TypedCCDConfig<Case, State, Role extends HasRole> extends CCDConfig<Case, State, Role> {

  Class<Case> caseDataClass();

  Class<State> stateClass();

  Class<Role> roleClass();
}
