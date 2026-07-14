package uk.gov.hmcts.ccd.sdk.api;

import java.util.List;

/**
 * The main Config Generator interface.
 *
 * <p>
 * Provide one or more implementations for your project, which the generator will find
 * and invoke during config generation.
 * </p>
 *
 * @param <Case>  Domain model representing case data.
 *                CCD schema is generated based on this class, including CaseField,
 *                ComplexType, FixedList etc.
 * @param <State> Enum representing Case's states.
 *                CCD States are generated based on this type.
 * @param <Role>  Enum representing Case's user roles.
 *                CCD roles are generated based on this type.
 */
public interface CCDConfig<Case, State, Role extends HasRole> {

  /**
   * Optional discriminator used when multiple independent case type configurations share the same case data class.
   *
   * <p>By default, configs with the same case data class are resolved together as one case type. Override this when
   * configs should instead be resolved as separate case types despite sharing the same data class.</p>
   */
  default String groupingKey() {
    return "";
  }

  /**
   * Optional grouping keys for a region-neutral component shared by several case types.
   * Existing single-group configurations continue to use {@link #groupingKey()}.
   */
  default List<String> groupingKeys() {
    return List.of(groupingKey());
  }

  /**
   * Invoked during config generation.
   *
   * @param builder Use to declare your CCD configuration.
   */
  default void configure(ConfigBuilder<Case, State, Role> builder) {
    throw new UnsupportedOperationException(
        "CCDConfig.configure is not implemented. Implement configureDecentralised(DecentralisedConfigBuilder) "
            + "or override configure(ConfigBuilder) to provide centralised configuration.");
  }

  /**
   * Optional hook for decentralised configuration. By default, delegates to {@link #configure(ConfigBuilder)}
   * to preserve backwards compatibility. Implementors may override to access decentralised-only APIs.
   */
  default void configureDecentralised(DecentralisedConfigBuilder<Case, State, Role> builder) {
    configure(builder);
  }
}
