package uk.gov.hmcts.ccd.sdk.api;

public interface CCDConfig<Case, State, Role extends HasCaseTypePerm, CaseRole extends HasCaseRole> {

  void configure(ConfigBuilder<Case, State, Role, CaseRole> builder);
}
