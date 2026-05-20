package uk.gov.hmcts.ccd.sdk.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackUrlNormalizerTest {

  private final CallbackUrlNormalizer normalizer = new CallbackUrlNormalizer();

  @Test
  void normalisesCallbackUrlToLocalPath() {
    assertEquals("/et3Response/submitSection",
        normalizer.normalisePath("${ET_COS_URL}/et3Response/submitSection"));
    assertEquals("/et3Response/submitSection",
        normalizer.normalisePath("http://localhost:4013/et3Response/submitSection?eventId=abc"));
    assertEquals("/et3Response/submitSection", normalizer.normalisePath("et3Response/submitSection/"));
  }

  @Test
  void detectsLocalCallbackUrls() {
    assertTrue(normalizer.isLocalCallbackUrl("${ET_COS_URL}/et3Response/submitSection", ""));
    assertTrue(normalizer.isLocalCallbackUrl("http://localhost:4013/et3Response/submitSection", ""));
    assertTrue(normalizer.isLocalCallbackUrl("https://et.local/callback", "https://et.local"));
    assertFalse(normalizer.isLocalCallbackUrl("https://other.service/callback", "https://et.local"));
  }
}
