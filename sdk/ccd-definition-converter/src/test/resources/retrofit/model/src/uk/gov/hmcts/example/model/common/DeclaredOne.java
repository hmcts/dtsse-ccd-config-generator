package uk.gov.hmcts.example.model.common;

/**
 * One of the two DIFFERENT classes the definition type {@code disagreeingCT} is referenced as. An ID
 * declared two ways has no single backing class, so {@link RetrofitTypeBinder} refuses to bind it to
 * whichever referencing field it happened to read first.
 */
public class DeclaredOne {

  private String detail;
}
