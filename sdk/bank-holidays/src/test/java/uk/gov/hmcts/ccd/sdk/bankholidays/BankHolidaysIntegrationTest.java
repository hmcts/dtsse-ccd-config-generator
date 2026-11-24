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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
  private final AtomicInteger fetches = new java.util.concurrent.atomic.AtomicInteger();

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
      fetches.incrementAndGet();
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
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(CHRISTMAS_2026)).isTrue();
      assertThat(source()).isEqualTo("SEED");
    });
  }

  @Test
  void knowsEachDivisionsOwnBankHolidays() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      LocalDate secondJanuary = LocalDate.of(2026, 1, 2);
      LocalDate stPatricksDay = LocalDate.of(2026, 3, 17);

      assertThat(bankHolidays.calendar(SCOTLAND).isBankHoliday(secondJanuary)).isTrue();
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(secondJanuary)).isFalse();
      assertThat(bankHolidays.calendar(NORTHERN_IRELAND).isBankHoliday(stPatricksDay)).isTrue();
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(stPatricksDay)).isFalse();
      assertThat(bankHolidays.holidays(ENGLAND_AND_WALES, LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 2)))
          .extracting(BankHoliday::title)
          .containsExactly("Christmas Day", "Boxing Day", "New Year’s Day");
    });
  }

  @Test
  void shiftsByWorkingDaysPastWeekendsAndBankHolidays() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      WorkingDayCalendar calendar = bankHolidays.calendar(ENGLAND_AND_WALES);
      LocalDate christmasEve2026 = LocalDate.of(2026, 12, 24);
      LocalDate tuesdayAfter = LocalDate.of(2026, 12, 29);

      // Christmas Day is a Friday, and Boxing Day's substitute the Monday after.
      assertThat(calendar.shift(christmasEve2026, 1)).isEqualTo(tuesdayAfter);
      assertThat(calendar.shift(tuesdayAfter, -1)).isEqualTo(christmasEve2026);
      assertThat(calendar.shift(CHRISTMAS_2026, 1)).isEqualTo(tuesdayAfter);
      assertThat(calendar.shift(CHRISTMAS_2026, 0)).isEqualTo(CHRISTMAS_2026);
      assertThat(calendar.isWorkingDay(LocalDate.of(2026, 12, 28))).isFalse();
    });
  }

  @Test
  void rollsOntoAWorkingDay() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      WorkingDayCalendar calendar = bankHolidays.calendar(ENGLAND_AND_WALES);

      assertThat(calendar.nextOrSame(CHRISTMAS_2026)).isEqualTo(LocalDate.of(2026, 12, 29));
      assertThat(calendar.previousOrSame(LocalDate.of(2026, 12, 28))).isEqualTo(LocalDate.of(2026, 12, 24));
      assertThat(calendar.nextOrSame(LocalDate.of(2026, 12, 24))).isEqualTo(LocalDate.of(2026, 12, 24));
    });
  }

  @Test
  void refusesDatesInYearsGovUkHasNotPublished() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      WorkingDayCalendar calendar = bankHolidays.calendar(ENGLAND_AND_WALES);
      LocalDate knownUntil = calendar.knownUntil();

      assertThat(knownUntil).isEqualTo(LocalDate.of(2028, 12, 31));
      assertThatThrownBy(() -> calendar.isWorkingDay(knownUntil.plusDays(1)))
          .isInstanceOf(UnknownBankHolidaysException.class)
          .hasMessageContaining("known from 2019-01-01 to 2028-12-31");
      assertThatThrownBy(() -> calendar.isBankHoliday(LocalDate.of(2018, 12, 25)))
          .isInstanceOf(UnknownBankHolidaysException.class);
      assertThatThrownBy(() -> calendar.shift(knownUntil.minusDays(3), 5))
          .isInstanceOf(UnknownBankHolidaysException.class);
      assertThatThrownBy(() -> bankHolidays.holidays(ENGLAND_AND_WALES, CHRISTMAS_2026, knownUntil.plusDays(2)))
          .isInstanceOf(UnknownBankHolidaysException.class);
    });
  }

  @Test
  void keepsACalendarTakenBeforeARefresh() {
    LocalDate oneOff = LocalDate.of(2026, 6, 1);
    feed = withEvent(seedFeed(), "england-and-wales", oneOff, "A one-off bank holiday").toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      WorkingDayCalendar before = bankHolidays.calendar(ENGLAND_AND_WALES);

      refresher.refresh();

      assertThat(before.isBankHoliday(oneOff)).isFalse();
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(oneOff)).isTrue();
    });
  }

  @Test
  void takesTheBankHolidaysGovUkPublishes() {
    LocalDate oneOff = LocalDate.of(2026, 6, 1);
    feed = withEvent(seedFeed(), "england-and-wales", oneOff, "A one-off bank holiday").toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(oneOff)).isFalse();

      refresher.refresh();

      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(oneOff)).isTrue();
      assertThat(bankHolidays.calendar(SCOTLAND).isBankHoliday(oneOff)).isFalse();
      assertThat(source()).isEqualTo("GOV_UK");
      assertThat(fetchError()).isNull();
    });
  }

  @Test
  void fetchesOnceADayForAllInstances() {
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();
      refresher.refresh();
    });
    // Another instance, or a restart.
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> refresher.refresh());

    assertThat(fetches).hasValue(1);

    ageLastFetch();
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> refresher.refresh());

    assertThat(fetches).hasValue(2);
  }

  @Test
  void leavesTheBankHolidaysAloneWhenGovUkPublishesNoChange() {
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();
      Timestamp updated = jdbc.queryForObject("select updated_at from bank_holidays.fetch_state", Timestamp.class);
      Timestamp fetched = jdbc.queryForObject("select fetched_at from bank_holidays.fetch_state", Timestamp.class);
      ageLastFetch();

      refresher.refresh();

      assertThat(jdbc.queryForObject("select updated_at from bank_holidays.fetch_state", Timestamp.class))
          .isEqualTo(updated);
      assertThat(jdbc.queryForObject("select fetched_at from bank_holidays.fetch_state", Timestamp.class))
          .isAfter(fetched);
      assertThat(source()).isEqualTo("GOV_UK");
    });
  }

  @Test
  void keepsTheBankHolidaysWhenGovUkFailsAndTriesAgainAtTheNextCheck() {
    LocalDate oneOff = LocalDate.of(2026, 6, 1);
    feed = withEvent(seedFeed(), "england-and-wales", oneOff, "A one-off bank holiday").toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();
      ageLastFetch();
      status = 503;

      refresher.refresh();

      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(oneOff)).isTrue();
      assertThat(fetchError()).contains("HTTP 503");

      // Not again within the hour, then at the next check.
      refresher.refresh();
      assertThat(fetches).hasValue(2);
      jdbc.update("update bank_holidays.fetch_state set last_attempt_at = last_attempt_at - interval '1 hour'");
      status = 200;
      refresher.refresh();
      assertThat(fetches).hasValue(3);
      assertThat(fetchError()).isNull();
    });
  }

  @Test
  void refusesAFeedMissingADivision() {
    ObjectNode partial = seedFeed();
    partial.remove("scotland");
    feed = partial.toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(bankHolidays.calendar(SCOTLAND).isBankHoliday(LocalDate.of(2026, 1, 2))).isTrue();
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
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(CHRISTMAS_2026)).isTrue();
      assertThat(source()).isEqualTo("SEED");
    });
  }

  @Test
  void keepsStoredBankHolidaysThatReachAsFarAsTheSdksCopy() {
    LocalDate oneOff = LocalDate.of(2026, 6, 1);
    feed = withEvent(seedFeed(), "england-and-wales", oneOff, "A one-off bank holiday").toString();
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> refresher.refresh());

    // A restart a day later while GOV.UK is down.
    ageLastFetch();
    status = 503;
    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(oneOff)).isTrue();
    });
  }

  @Test
  void loadsTheBankHolidaysAtStartup() {
    withBankHolidays("", (bankHolidays, refresher) -> assertThat(source()).isEqualTo("SEED"));
  }

  @Test
  void refusesWeekendsAndShiftsOutsideTheKnownYears() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      WorkingDayCalendar calendar = bankHolidays.calendar(ENGLAND_AND_WALES);
      LocalDate unknownSaturday = LocalDate.of(2018, 12, 29);

      assertThatThrownBy(() -> calendar.isWorkingDay(unknownSaturday))
          .isInstanceOfSatisfying(UnknownBankHolidaysException.class, e -> {
            assertThat(e.division()).isEqualTo(ENGLAND_AND_WALES);
            assertThat(e.date()).isEqualTo(unknownSaturday);
          });
      assertThatThrownBy(() -> calendar.nextOrSame(unknownSaturday))
          .isInstanceOf(UnknownBankHolidaysException.class);
      assertThatThrownBy(() -> calendar.shift(CHRISTMAS_2026, Integer.MIN_VALUE))
          .isInstanceOf(UnknownBankHolidaysException.class);
    });
  }

  @Test
  void refusesAReversedRangeAndGivesNoneForAnEmptyOne() {
    withBankHolidays("", (bankHolidays, refresher) -> {
      WorkingDayCalendar calendar = bankHolidays.calendar(ENGLAND_AND_WALES);

      assertThat(calendar.holidays(CHRISTMAS_2026, CHRISTMAS_2026)).isEmpty();
      assertThatThrownBy(() -> calendar.holidays(CHRISTMAS_2026, CHRISTMAS_2026.minusDays(1)))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test
  void keepsYearsGovUkStopsPublishing() {
    ObjectNode later = seedFeed();
    later.properties().forEach(division -> {
      ArrayNode events = (ArrayNode) division.getValue().path("events");
      for (int i = events.size() - 1; i >= 0; i--) {
        if (events.get(i).path("date").asText().startsWith("2019")) {
          events.remove(i);
        }
      }
    });
    feed = later.toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(source()).isEqualTo("GOV_UK");
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(LocalDate.of(2019, 12, 25))).isTrue();
    });
  }

  @Test
  void refusesAFeedMissingAYear() {
    ObjectNode gap = seedFeed();
    ArrayNode events = (ArrayNode) gap.path("england-and-wales").path("events");
    for (int i = events.size() - 1; i >= 0; i--) {
      if (events.get(i).path("date").asText().startsWith("2024")) {
        events.remove(i);
      }
    }
    feed = gap.toString();

    withBankHolidays(govUkUrl(), (bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(fetchError()).contains("miss a year between 2019 and 2028");
      assertThat(bankHolidays.calendar(ENGLAND_AND_WALES).isBankHoliday(LocalDate.of(2024, 12, 25))).isTrue();
    });
  }

  @Test
  void anApplicationsOwnBankHolidaysReplaceTheStoreAndRefresh() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BankHolidaysAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withBean(BankHolidays.class, BankHolidays::bundled)
        .withPropertyValues("ccd.bank-holidays.url=" + govUkUrl())
        .run(context -> {
          assertThat(context).hasSingleBean(BankHolidays.class).doesNotHaveBean(BankHolidaysRefresher.class);
          assertThat(context.getBean(BankHolidays.class).calendar(ENGLAND_AND_WALES).isBankHoliday(CHRISTMAS_2026))
              .isTrue();
        });

    assertThat(jdbc.queryForObject("select count(*) from bank_holidays.fetch_state", Integer.class)).isZero();
    assertThat(fetches).hasValue(0);
  }

  @Test
  void refusesTwoBankHolidaysOnOneDate() {
    BankHoliday christmas = new BankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026, "Christmas Day", "");

    assertThatThrownBy(() -> BankHolidays.of(List.of(christmas, christmas)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** An instance of the application, fetching from the URL, or not fetching when it is empty. */
  private void withBankHolidays(String url, BankHolidaysTest test) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BankHolidaysAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withPropertyValues(url.isEmpty() ? "ccd.bank-holidays.refresh.enabled=false" : "ccd.bank-holidays.url=" + url)
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

  private static void ageLastFetch() {
    jdbc.update("update bank_holidays.fetch_state set fetched_at = fetched_at - interval '1 day', "
        + "last_attempt_at = last_attempt_at - interval '1 day'");
  }

  private static String fetchError() {
    return jdbc.queryForObject("select last_attempt_error from bank_holidays.fetch_state", String.class);
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
