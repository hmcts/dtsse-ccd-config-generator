package uk.gov.hmcts.ccd.sdk.converter.model;

import lombok.Builder;
import lombok.Value;

/**
 * The jurisdiction-wide service notice banner, from the {@code Banner} sheet. The importer allows
 * exactly one banner per jurisdiction, with four columns (BannerEnabled/BannerDescription/
 * BannerURL/BannerURLText); the config emitter reproduces it via {@code ConfigBuilder.banner(...)}.
 */
@Value
@Builder
public class BannerModel {

  boolean enabled;
  String description;
  String url;
  String urlText;
}
