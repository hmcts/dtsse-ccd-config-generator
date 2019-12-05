package ccd.sdk.types;

public interface CCDConfig<T> {
  void configure(ConfigBuilder<T> builder);
}
