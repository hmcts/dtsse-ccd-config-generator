package uk.gov.hmcts.ccd.sdk.bankholidays;

/** The UK divisions GOV.UK publishes bank holidays for. */
public enum Division {
  ENGLAND_AND_WALES("england-and-wales"),
  SCOTLAND("scotland"),
  NORTHERN_IRELAND("northern-ireland");

  private final String govUkName;

  Division(String govUkName) {
    this.govUkName = govUkName;
  }

  /**
   * The division's name in GOV.UK's feed and URLs, such as {@code england-and-wales}.
   */
  public String govUkName() {
    return govUkName;
  }
}
