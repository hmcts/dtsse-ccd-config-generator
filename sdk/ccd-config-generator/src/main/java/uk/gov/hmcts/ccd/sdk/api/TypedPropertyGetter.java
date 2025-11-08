package uk.gov.hmcts.ccd.sdk.api;

import java.io.Serializable;

/**
 * Typed method reference to a bean property getter. Declared as {@link Serializable} so that
 * reflective access to the underlying lambda metadata is possible when resolving property details.
 *
 * @param <T> the type declaring the property
 * @param <R> the property value type
 */
@FunctionalInterface
public interface TypedPropertyGetter<T, R> extends Serializable {

  R get(T source);
}
