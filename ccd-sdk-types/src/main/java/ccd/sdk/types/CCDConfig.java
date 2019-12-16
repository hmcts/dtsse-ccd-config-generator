package ccd.sdk.types;

public interface CCDConfig<Case, R extends Role> {
  void configure(ConfigBuilder<Case, R> builder);
}
