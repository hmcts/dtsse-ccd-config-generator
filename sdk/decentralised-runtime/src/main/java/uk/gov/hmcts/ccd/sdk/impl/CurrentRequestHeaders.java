package uk.gov.hmcts.ccd.sdk.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class CurrentRequestHeaders {

  private CurrentRequestHeaders() {
  }

  public static String get(String name) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
      HttpServletRequest request = servletAttributes.getRequest();
      String value = request.getHeader(name);
      return value == null ? "" : value;
    }
    return "";
  }
}
