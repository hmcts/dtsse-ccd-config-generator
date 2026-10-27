package uk.gov.hmcts.example.enums;

/**
 * An enum whose constants share one source line — the shape civil's {@code ListingOrRelisting} is
 * written in. {@code @CCD} is not {@code @Repeatable}, so the label pin cannot stack both constants'
 * annotations above the shared line; it must split the line into one constant per line first.
 */
public enum SharedLineList {

  FIRST, SECOND, THIRD;
}
