package uk.gov.hmcts.ccd.sdk.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A callback the service already serves at its own endpoint, written to the definition as given.
 *
 * @param url the callback URL, verbatim, so placeholders such as {@code ${CCD_DEF_URL}} are left
 *     for the definition processor
 * @param retries the retry timeouts joined with commas, or null for none
 */
public record CallbackUrl(String url, String retries) {

  public CallbackUrl {
    Objects.requireNonNull(url, "url");
  }

  static CallbackUrl of(String url, int... retries) {
    String joined = retries.length == 0 ? null
        : Arrays.stream(retries).mapToObj(String::valueOf).collect(Collectors.joining(","));
    return new CallbackUrl(url, joined);
  }
}
