package uk.gov.hmcts.ccd.sdk.api;

import java.util.Set;

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
   * Case types to which this configuration should be applied.
   *
   * <p>Leave empty for the default configuration associated with a case data class. Configurations
   * sharing a case data class across multiple case types must declare the same case type IDs as the
   * corresponding case type definitions.</p>
   */
  default Set<String> caseTypeIds() {
    return Set.of();
  }

  /**
   * Controls the order in which configurations in the same group are applied.
   *
   * <p>Higher-priority configurations are applied later and therefore replace duplicate event
   * definitions from lower-priority configurations.</p>
   */
  default int configurationPriority() {
    return 0;
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
