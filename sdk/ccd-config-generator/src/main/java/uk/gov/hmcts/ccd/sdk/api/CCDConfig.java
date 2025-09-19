package uk.gov.hmcts.ccd.sdk.api;

/**
 * The main Config Generator interface.
 *
 * <p>
 * Provide one or more implementations for your project, which the generator will find
 * and invoke during config generation.
 * </p>
 *
 * @param <Case> Domain model representing case data.
 *     CCD schema is generated based on this class, including CaseField,
 *     ComplexType, FixedList etc.
 * @param <State> Enum representing Case's states.
 *     CCD States are generated based on this type.
 * @param <Role> Enum representing Case's user roles.
 *     CCD roles are generated based on this type.
 */
public interface CCDConfig<Case, State, Role extends HasRole> {

  /**
   * Invoked during config generation.
   *
   * @param builder Use to declare your CCD configuration.
   */
  void configure(ConfigBuilder<Case, State, Role> builder);
}
