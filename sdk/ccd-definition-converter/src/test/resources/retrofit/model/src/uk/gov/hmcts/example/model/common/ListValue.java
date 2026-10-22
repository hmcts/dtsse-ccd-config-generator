package uk.gov.hmcts.example.model.common;

/**
 * A generic collection wrapper ({@code id} + {@code value}) like FPL's {@code Element<T>} or the
 * SDK's {@code ListValue<T>}: {@code List<ListValue<X>>} descends to {@code X} via the SDK's
 * {@code hasGenerics()} rule (a generic wrapper, NOT a concrete one).
 */
public class ListValue<T> {

  private String id;

  private T value;
}
