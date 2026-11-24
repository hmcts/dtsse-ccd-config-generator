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
import java.util.SortedSet;
import java.util.TreeSet;

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
   * and so on for each division. A feed missing a division, or a year between a division's first
   * and last, is refused, so it never replaces bank holidays that had them.
   */
  static List<BankHoliday> parse(JsonNode feed) {
    List<BankHoliday> holidays = new ArrayList<>();
    for (Division division : Division.values()) {
      JsonNode events = feed.path(division.govUkName()).path("events");
      if (!events.isArray() || events.isEmpty()) {
        throw new IllegalArgumentException("GOV.UK bank holidays have no " + division.govUkName() + " events");
      }
      SortedSet<Integer> years = new TreeSet<>();
      events.forEach(event -> {
        LocalDate date = LocalDate.parse(event.path("date").asText());
        years.add(date.getYear());
        holidays.add(new BankHoliday(division, date, event.path("title").asText(), event.path("notes").asText("")));
      });
      if (years.size() != years.last() - years.first() + 1) {
        throw new IllegalArgumentException("GOV.UK bank holidays for " + division.govUkName()
            + " miss a year between " + years.first() + " and " + years.last());
      }
    }
    return holidays;
  }
}
