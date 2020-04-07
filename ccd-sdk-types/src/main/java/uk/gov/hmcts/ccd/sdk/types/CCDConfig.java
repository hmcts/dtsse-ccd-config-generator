package uk.gov.hmcts.ccd.sdk.types;

public interface CCDConfig<Case, State, Role extends HasRole> {

  void configure(ConfigBuilder<Case, State, Role> builder);
}
