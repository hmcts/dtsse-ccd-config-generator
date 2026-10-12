package uk.gov.hmcts.ccd.sdk.converter.model;

import lombok.Value;

/**
 * A callback the definition points at the service's own endpoint: an event's about-to-start,
 * about-to-submit or submitted hook, or a wizard page's mid-event hook.
 */
@Value
public class CallbackModel {

  /**
   * The URL as authored, placeholders such as {@code ${CCD_DEF_IA_URL}} included.
   */
  String url;

  /**
   * The hook's retry timeouts as authored (e.g. {@code "5,5,5,5"}), or null when absent.
   */
  String retries;
}
