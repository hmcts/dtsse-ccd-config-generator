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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
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
  private static final LocalDate ONE_OFF = LocalDate.of(2026, 6, 1);

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
  void seedsAnEmptyStoreFromTheSdksCopy() {
    withBankHolidays((bankHolidays, refresher) -> {
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026)).isTrue();
      assertThat(stored()).isPositive();
    });
  }

  @Test
  void addsTheYearsTheSdksCopyHasThatTheStoreLacks() {
    jdbc.update("insert into bank_holidays.bank_holiday (division, date, title, notes) values "
        + "('ENGLAND_AND_WALES', '2020-12-25', 'Fetched from GOV.UK', '')");

    withBankHolidays((bankHolidays, refresher) -> {
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026)).isTrue();
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, LocalDate.of(2020, 1, 1))).isFalse();
    });
  }

  @Test
  void knowsEachDivisionsOwnBankHolidays() {
    withBankHolidays((bankHolidays, refresher) -> {
      LocalDate secondJanuary = LocalDate.of(2026, 1, 2);
      LocalDate stPatricksDay = LocalDate.of(2026, 3, 17);

      assertThat(bankHolidays.isBankHoliday(SCOTLAND, secondJanuary)).isTrue();
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, secondJanuary)).isFalse();
      assertThat(bankHolidays.isBankHoliday(NORTHERN_IRELAND, stPatricksDay)).isTrue();
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, stPatricksDay)).isFalse();
      assertThat(bankHolidays.holidays(ENGLAND_AND_WALES))
          .extracting(BankHoliday::date)
          .isSorted()
          .contains(CHRISTMAS_2026);
    });
  }

  @Test
  void refusesDatesInYearsWithNoBankHolidaysKnown() {
    BankHolidays bankHolidays = BankHolidays.of(List.of(
        new BankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026, "Christmas Day", ""),
        new BankHoliday(ENGLAND_AND_WALES, LocalDate.of(2028, 12, 25), "Christmas Day", "")));

    assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, LocalDate.of(2026, 12, 24))).isFalse();
    assertThatThrownBy(() -> bankHolidays.isBankHoliday(ENGLAND_AND_WALES, LocalDate.of(2027, 12, 25)))
        .isInstanceOf(UnknownBankHolidaysException.class)
        .hasMessage("ENGLAND_AND_WALES bank holidays are not known for 2027");
    assertThatThrownBy(() -> bankHolidays.isBankHoliday(SCOTLAND, CHRISTMAS_2026))
        .isInstanceOf(UnknownBankHolidaysException.class);
  }

  @Test
  void takesTheBankHolidaysGovUkPublishes() {
    feed = withEvent(seedFeed(), ONE_OFF).toString();

    withBankHolidays((bankHolidays, refresher) -> {
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, ONE_OFF)).isFalse();

      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, ONE_OFF)).isTrue();
    });
    // Another instance, or a restart, reads them from the store.
    withBankHolidays((bankHolidays, refresher) ->
        assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, ONE_OFF)).isTrue());
  }

  @Test
  void takesGovUksCorrectionsToAYearItPublishes() {
    feed = withEvent(seedFeed(), ONE_OFF).toString();
    withBankHolidays((bankHolidays, refresher) -> refresher.refresh());

    feed = seedFeed().toString();
    withBankHolidays((bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, ONE_OFF)).isFalse();
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

    withBankHolidays((bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, LocalDate.of(2019, 12, 25))).isTrue();
    });
  }

  @Test
  void keepsTheBankHolidaysWhenGovUkFails() {
    feed = withEvent(seedFeed(), ONE_OFF).toString();
    withBankHolidays((bankHolidays, refresher) -> refresher.refresh());

    status = 503;
    withBankHolidays((bankHolidays, refresher) -> {
      refresher.refresh();

      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, ONE_OFF)).isTrue();
    });
  }

  @Test
  void startsWithoutItsDatabaseAndReadsTheStoreOnTheFirstLookup() {
    // Nothing listens on port 1, as when an application starts to generate its CCD definition.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BankHolidaysAutoConfiguration.class))
        .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:postgresql://localhost:1/none"))
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(BankHolidays.class));

    withBankHolidays((bankHolidays, refresher) -> {
      assertThat(stored()).isZero();
      assertThat(bankHolidays.isBankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026)).isTrue();
      assertThat(stored()).isPositive();
    });
  }

  @Test
  void anApplicationsOwnBankHolidaysReplaceTheStoreAndFetch() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BankHolidaysAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withBean(BankHolidays.class, BankHolidays::bundled)
        .run(context -> {
          assertThat(context).hasSingleBean(BankHolidays.class).doesNotHaveBean(BankHolidaysRefresher.class);
          assertThat(context.getBean(BankHolidays.class).isBankHoliday(ENGLAND_AND_WALES, CHRISTMAS_2026)).isTrue();
        });

    assertThat(stored()).isZero();
  }

  private void withBankHolidays(BiConsumer<BankHolidays, BankHolidaysRefresher> test) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BankHolidaysAutoConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withPropertyValues("ccd.bank-holidays.url=http://localhost:" + govUk.getAddress().getPort()
            + "/bank-holidays.json")
        .run(context -> {
          assertThat(context).hasNotFailed();
          test.accept(context.getBean(BankHolidays.class), context.getBean(BankHolidaysRefresher.class));
        });
  }

  private static int stored() {
    return jdbc.queryForObject("select count(*) from bank_holidays.bank_holiday", Integer.class);
  }

  private static ObjectNode seedFeed() {
    try (InputStream seed = BankHolidaysIntegrationTest.class.getClassLoader()
        .getResourceAsStream(GovUkBankHolidays.SEED)) {
      return (ObjectNode) JSON.readTree(seed);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ObjectNode withEvent(ObjectNode feed, LocalDate date) {
    ((ArrayNode) feed.path("england-and-wales").path("events")).add(JSON.valueToTree(
        Map.of("title", "A one-off bank holiday", "date", date.toString(), "notes", "", "bunting", true)));
    return feed;
  }
}
