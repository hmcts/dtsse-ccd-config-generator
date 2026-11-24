package uk.gov.hmcts.ccd.sdk.bankholidays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The UK divisions GOV.UK publishes bank holidays for. */
@Getter
@RequiredArgsConstructor
public enum Division {
  ENGLAND_AND_WALES("england-and-wales"),
  SCOTLAND("scotland"),
  NORTHERN_IRELAND("northern-ireland");

  /** The division's name in GOV.UK's feed and URLs. */
  private final String govUkName;
}
