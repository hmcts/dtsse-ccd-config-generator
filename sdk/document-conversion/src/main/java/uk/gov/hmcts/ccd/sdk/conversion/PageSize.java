package uk.gov.hmcts.ccd.sdk.conversion;

/** Output paper sizes, portrait, in millimetres. */
public enum PageSize {
  A4(210, 297),
  A3(297, 420),
  A5(148, 210),
  LETTER(215.9, 279.4);

  private final double widthMillimetres;
  private final double heightMillimetres;

  PageSize(double widthMillimetres, double heightMillimetres) {
    this.widthMillimetres = widthMillimetres;
    this.heightMillimetres = heightMillimetres;
  }

  /** The portrait width in millimetres. */
  public double widthMillimetres() {
    return widthMillimetres;
  }

  /** The portrait height in millimetres. */
  public double heightMillimetres() {
    return heightMillimetres;
  }
}
