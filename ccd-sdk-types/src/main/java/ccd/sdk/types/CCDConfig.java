package ccd.sdk.types;

public interface CCDConfig<Case, S, R extends Role> {
  void configure(ConfigBuilder<Case, S, R> builder);
}
