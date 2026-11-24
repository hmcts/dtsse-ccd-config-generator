package uk.gov.hmcts.ccd.sdk.bankholidays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.ccd.sdk.bankholidays.Division.ENGLAND_AND_WALES;
import static uk.gov.hmcts.ccd.sdk.bankholidays.Division.NORTHERN_IRELAND;
import static uk.gov.hmcts.ccd.sdk.bankholidays.Division.SCOTLAND;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.ccd.sdk.config.DecentralisedFlywayAutoConfiguration;

@Testcontainers
class BankHolidaysIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final LocalDate CHRISTMAS_2026 = LocalDate.of(2026, 12, 25);

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;

  private HttpServer govUk;
  private volatile int status;
  private volatile String feed;

  @BeforeAll
  static void migrate() {
    dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DecentralisedFlywayAutoConfiguration.class,
            BankHolidaysFlywayAutoConfiguration.class, FlywayAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .run(context -> assertThat(context).hasNotFailed());
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void startGovUk() throws Exception {
    jdbc.update("delete from bank_holidays.bank_holiday");
    jdbc.update("delete from bank_holidays.fetch_state");
    status = 200;
    feed = seedFeed().toString();
    govUk = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    govUk.createContext("/bank-holidays.json", exchange -> {
      byte[] body = feed.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    govUk.start();
  }

  @AfterEach
  void stopGovUk() {
    govUk.stop(0);
  }

  @Test
  void seedsTheBankHolidaysFromTheSdkBeforeTheFirstFetch() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026)).isTrue();
      assertThat(source()).isEqualTo("SEED");
    });
  }

  @Test
  void knowsEachDivisionsOwnBankHolidays() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      LocalDate secondJanuary = LocalDate.of(2026, 1, 2);
      LocalDate stPatricksDay = LocalDate.of(2026, 3, 17);

      assertThat(bankHolidays.isBankHoliday(SCOTLAND, secondJanuary)).isTrue();
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, secondJanuary)).isFalse();
      assertThat(bankHolidays.isBankHoliday(NORTHERN_IRELAND, stPatricksDay)).isTrue();
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, stPatricksDay)).isFalse();
      assertThat(bankHolidays.between(ENGLAND_AND_WALES, LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 2)))
          .extracting(BankHoliday::title)
          .containsExactly("Christmas Day", "Boxing Day", "New Year’s Day");
    });
  }

  @Test
  void countsWorkingDaysPastWeekendsAndBankHolidays() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      LocalDate christmasEve2026 = LocalDate.of(2026, 12, 24);

      // Christmas Day is a Friday, and Boxing Day's substitute the Monday after.
      assertThat(bankHolidays.addWorkingDays(ENGLAND_AND_WALES, christmasEve2026, 1))
          .isEqualTo(LocalDate.of(2026, 12, 29));
      assertThat(bankHolidays.addWorkingDays(ENGLAND_AND_WALES, LocalDate.of(2026, 12, 29), -1))
          .isEqualTo(christmasEve2026);
      assertThat(bankHolidays.addWorkingDays(ENGLAND_AND_WALES, CHRISTMAS_2026, 0)).isEqualTo(CHRISTMAS_2026);
      assertThat(bankHolidays.isWorkingDay(ENGLAND_AND_WALES, LocalDate.of(2026, 12, 26))).isFalse();
    });
  }

  @Test
  void refusesDatesInYearsGovUkHasNotPublished() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      LocalDate knownUntil = bankHolidays.knownUntil(ENGLAND_AND_WALES);

      assertThat(knownUntil).isEqualTo(LocalDate.of(2028, 12, 31));
      assertThat(bankHolidays.knownFrom(ENGLAND_AND_WALES)).isEqualTo(LocalDate.of(2019, 1, 1));
      assertThatThrownBy(() -> bankHolidays.isWorkingDay(ENGLAND_AND_WALES, knownUntil.plusDays(1)))
          .isInstanceOf(UnknownBankHolidaysException.class)
          .hasMessageContaining("known from 2019-01-01 to 2028-12-31");
      assertThatThrownBy(() -> bankHolidays.addWorkingDays(ENGLAND_AND_WALES, knownUntil.minusDays(3), 5))
          .isInstanceOf(UnknownBankHolidaysException.class);
      assertThatThrownBy(() -> bankHolidays.between(ENGLAND_AND_WALES, CHRISTMAS_2026, knownUntil.plusDays(2)))
          .isInstanceOf(UnknownBankHolidaysException.class);
    });
  }

  @Test
  void takesTheBankHolidaysGovUkPublishes() {
    LocalDate oneOff = LocalDate.of(2026, 6, 1);
    feed = withEvent(seedFeed(), "england-and-wales", oneOff, "A one-off bank holiday").toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, oneOff)).isFalse();

      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, oneOff)).isTrue();
      assertThat(bankHolidays.isBankHoliday(SCOTLAND, oneOff)).isFalse();
      assertThat(source()).isEqualTo("GOV_UK");
      assertThat(fetchError()).isNull();
    });
  }

  @Test
  void leavesTheBankHolidaysAloneWhenGovUkPublishesNoChange() {
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();
      Timestamp updated = jdbc.queryForObject("select updated_at from bank_holidays.fetch_state", Timestamp.class);
      Timestamp fetched = jdbc.queryForObject("select last_fetch_at from bank_holidays.fetch_state", Timestamp.class);

      refresher.refresh();

      assertThat(jdbc.queryForObject("select updated_at from bank_holidays.fetch_state", Timestamp.class))
          .isEqualTo(updated);
      assertThat(jdbc.queryForObject("select last_fetch_at from bank_holidays.fetch_state", Timestamp.class))
          .isAfter(fetched);
      assertThat(source()).isEqualTo("GOV_UK");
    });
  }

  @Test
  void keepsTheBankHolidaysWhenGovUkFails() {
    LocalDate oneOff = LocalDate.of(2026, 6, 1);
    feed = withEvent(seedFeed(), "england-and-wales", oneOff, "A one-off bank holiday").toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();
      status = 503;

      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, oneOff)).isTrue();
      assertThat(fetchError()).contains("HTTP 503");
    });
  }

  @Test
  void refusesAFeedMissingADivision() {
    ObjectNode partial = seedFeed();
    partial.remove("scotland");
    feed = partial.toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(SCOTLAND, LocalDate.of(2026, 1, 2))).isTrue();
      assertThat(source()).isEqualTo("SEED");
      assertThat(fetchError()).contains("no scotland events");
    });
  }

  @Test
  void prefersTheSdksCopyToStoredBankHolidaysItReachesBeyond() {
    jdbc.update("insert into bank_holidays.bank_holiday (division, date, title, notes) values "
        + "('ENGLAND_AND_WALES', '2020-12-25', 'Christmas Day', '')");
    jdbc.update("insert into bank_holidays.fetch_state (source, updated_at) values ('GOV_UK', now())");

    withBankHolidays("", (bankHolidays, refresher) -> {
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026)).isTrue();
      assertThat(source()).isEqualTo("SEED");
    });
  }

  @Test
  void keepsStoredBankHolidaysThatReachAsFarAsTheSdksCopy() {
    LocalDate oneOff = LocalDate.of(2026, 6, 1);
    feed = withEvent(seedFeed(), "england-and-wales", oneOff, "A one-off bank holiday").toString();
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> refresher.refresh());

    // A restart while GOV.UK is down.
    status = 503;
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, oneOff)).isTrue();
    });
  }

  private void withBankHolidays(String url, BankHolidaysTest test) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BankHolidaysAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withPropertyValues("ccd.bank-holidays.url=" + url)
        .run(context -> {
          assertThat(context).hasNotFailed();
          test.run(context.getBean(BankHolidays.class), context.getBean(BankHolidaysRefresher.class));
        });
  }

  private String govUkUrl() {
    return "http://localhost:" + govUk.getAddress().getPort() + "/bank-holidays.json";
  }

  private static String source() {
    return jdbc.queryForObject("select source from bank_holidays.fetch_state", String.class);
  }

  private static String fetchError() {
    return jdbc.queryForObject("select last_fetch_error from bank_holidays.fetch_state", String.class);
  }

  private static ObjectNode seedFeed() {
    try (InputStream seed = BankHolidaysIntegrationTest.class.getClassLoader()
        .getResourceAsStream(GovUkBankHolidays.SEED)) {
      return (ObjectNode) JSON.readTree(seed);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ObjectNode withEvent(ObjectNode feed, String division, LocalDate date, String title) {
    ((ArrayNode) feed.path(division).path("events"))
        .add(JSON.valueToTree(Map.of("title", title, "date", date.toString(), "notes", "", "bunting", true)));
    return feed;
  }

  @FunctionalInterface
  private interface BankHolidaysTest {
    void run(BankHolidays bankHolidays, BankHolidaysRefresher refresher) throws Exception;
  }
}
