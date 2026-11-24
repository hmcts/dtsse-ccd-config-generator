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
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final URI url;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
      .followRedirects(HttpClient.Redirect.NORMAL).build();

  GovUkBankHolidays(URI url) {
    this.url = url;
  }

  List<BankHoliday> fetch() throws IOException, InterruptedException {
    HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(url).timeout(TIMEOUT).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("GOV.UK bank holidays returned HTTP " + response.statusCode());
    }
    return parse(JSON.readTree(response.body()));
  }

  static List<BankHoliday> seed() {
    try (InputStream seed = GovUkBankHolidays.class.getClassLoader().getResourceAsStream(SEED)) {
      return parse(JSON.readTree(seed));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The feed's bank holidays: {@code {"england-and-wales": {"events": [{"title", "date", "notes"}]}}}
   * and so on for each division.
   */
  static List<BankHoliday> parse(JsonNode feed) {
    List<BankHoliday> holidays = new ArrayList<>();
    for (Division division : Division.values()) {
      feed.path(division.govUkName()).path("events").forEach(event -> holidays.add(new BankHoliday(division,
          LocalDate.parse(event.path("date").asText()), event.path("title").asText(),
          event.path("notes").asText(""))));
    }
    return holidays;
  }
}
