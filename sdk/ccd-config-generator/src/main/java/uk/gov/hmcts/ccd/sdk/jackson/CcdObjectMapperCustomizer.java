package uk.gov.hmcts.ccd.sdk.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Applied by the SDK to every Jackson 2 {@link ObjectMapper} bean once the bean is initialised,
 * including mappers a service declares itself and those a {@code FactoryBean} produces. Declare
 * one as a bean to have the SDK apply it; it must not depend on an {@code ObjectMapper} bean.
 */
@FunctionalInterface
public interface CcdObjectMapperCustomizer {

  void customize(ObjectMapper mapper);
}
