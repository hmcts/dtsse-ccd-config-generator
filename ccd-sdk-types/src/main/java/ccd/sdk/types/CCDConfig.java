package ccd.sdk.types;

public interface CCDConfig<Case> {
  void configure(ConfigBuilder<Case> builder);
}
