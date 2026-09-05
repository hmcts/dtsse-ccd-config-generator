package uk.gov.hmcts.ccd.sdk.api;

import lombok.Builder;
import lombok.Data;

/**
 * The jurisdiction-wide service notice shown by XUI, imported by {@code BannerParser}. The
 * importer allows exactly one banner per jurisdiction; calling {@link ConfigBuilder#banner}
 * more than once for the same case type overwrites the earlier value rather than adding a row.
 */
@Builder
@Data
public class Banner {
  private boolean enabled;
  private String description;
  private String url;
  private String urlText;
}
