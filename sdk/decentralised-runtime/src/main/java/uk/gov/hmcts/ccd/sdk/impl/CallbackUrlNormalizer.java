package uk.gov.hmcts.ccd.sdk.impl;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.stereotype.Component;

@Component
class CallbackUrlNormalizer {

  String normalisePath(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }

    String path = value.trim();
    int placeholderEnd = path.indexOf('}');
    if (path.startsWith("${") && placeholderEnd >= 0 && path.length() > placeholderEnd + 1) {
      path = path.substring(placeholderEnd + 1);
    }

    try {
      URI uri = new URI(path);
      if (uri.getPath() != null && !uri.getPath().isBlank()) {
        path = uri.getPath();
      }
    } catch (URISyntaxException ignored) {
      int queryStart = path.indexOf('?');
      if (queryStart >= 0) {
        path = path.substring(0, queryStart);
      }
      int fragmentStart = path.indexOf('#');
      if (fragmentStart >= 0) {
        path = path.substring(0, fragmentStart);
      }
    }

    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  boolean isLocalCallbackUrl(String value, String localCallbackBaseUrls) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String url = value.trim();
    if (url.startsWith("/") || url.startsWith("${")) {
      return true;
    }

    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException ex) {
      return true;
    }

    if (uri.getHost() == null) {
      return true;
    }
    String host = uri.getHost().toLowerCase();
    if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("host.docker.internal")) {
      return true;
    }

    if (localCallbackBaseUrls == null || localCallbackBaseUrls.isBlank()) {
      return false;
    }
    for (String baseUrl : localCallbackBaseUrls.split(",")) {
      String cleaned = baseUrl.trim();
      if (!cleaned.isBlank() && url.startsWith(cleaned)) {
        return true;
      }
    }
    return false;
  }
}
