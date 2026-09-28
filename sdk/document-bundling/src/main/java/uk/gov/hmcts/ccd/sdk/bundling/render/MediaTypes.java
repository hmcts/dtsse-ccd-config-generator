package uk.gov.hmcts.ccd.sdk.bundling.render;

import java.util.Locale;

final class MediaTypes {
  private MediaTypes() {
  }

  static String normalise(String mediaType) {
    if (mediaType == null) {
      return "";
    }
    String type = mediaType;
    int parameters = type.indexOf(';');
    if (parameters >= 0) {
      type = type.substring(0, parameters);
    }
    return type.trim().toLowerCase(Locale.ROOT);
  }
}
