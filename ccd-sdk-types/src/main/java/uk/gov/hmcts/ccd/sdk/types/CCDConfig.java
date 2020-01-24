package uk.gov.hmcts.ccd.sdk.types;

public interface CCDConfig<Case, State, Role extends uk.gov.hmcts.ccd.sdk.types.Role> {
  void configure(ConfigBuilder<Case, State, Role> builder);
}
