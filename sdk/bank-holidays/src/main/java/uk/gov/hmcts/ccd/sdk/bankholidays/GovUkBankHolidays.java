package uk.gov.hmcts.ccd.sdk.bankholidays;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** GOV.UK's bank holidays feed, fetched or as copied into the SDK. */
final class GovUkBankHolidays {

  static final String SEED = "bank-holidays-seed.json";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final URI url;
  private final Duration timeout;
  private final HttpClient client;

  GovUkBankHolidays(URI url, Duration timeout) {
    this.url = url;
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /** The bank holidays GOV.UK publishes now. */
  List<BankHoliday> fetch() throws IOException, InterruptedException {
    HttpResponse<byte[]> response = client.send(
        HttpRequest.newBuilder(url).timeout(timeout).header("Accept", "application/json").GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("GOV.UK bank holidays returned HTTP " + response.statusCode());
    }
    return parse(JSON.readTree(response.body()));
  }

  /** The copy of GOV.UK's feed in the SDK. */
  static List<BankHoliday> seed() {
    try (InputStream seed = GovUkBankHolidays.class.getClassLoader().getResourceAsStream(SEED)) {
      if (seed == null) {
        throw new IllegalStateException("The SDK's bank holidays seed " + SEED + " is missing");
      }
      return parse(JSON.readTree(seed));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The feed's bank holidays: {@code {"england-and-wales": {"events": [{"title", "date", "notes"}]}}}
   * and so on for each division. A feed missing a division is refused, so it never replaces one
   * that had it.
   */
  static List<BankHoliday> parse(JsonNode feed) {
    List<BankHoliday> holidays = new ArrayList<>();
    for (Division division : Division.values()) {
      JsonNode events = feed.path(division.govUkName()).path("events");
      if (!events.isArray() || events.isEmpty()) {
        throw new IllegalArgumentException("GOV.UK bank holidays have no " + division.govUkName() + " events");
      }
      events.forEach(event -> holidays.add(new BankHoliday(division,
          LocalDate.parse(event.path("date").asText()),
          event.path("title").asText(),
          event.path("notes").asText(""))));
    }
    return holidays;
  }
}
