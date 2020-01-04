package ccd.sdk.types;

public interface CCDConfig<Case, State, Role extends ccd.sdk.types.Role> {
  void configure(ConfigBuilder<Case, State, Role> builder);
}
