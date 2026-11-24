# Bank holidays

`sdk/bank-holidays` (`com.github.hmcts:bank-holidays`) gives the UK's bank holidays as
[GOV.UK publishes them](https://www.gov.uk/bank-holidays.json), for England and Wales, Scotland and
Northern Ireland, and working days by them.

```groovy
implementation 'com.github.hmcts:bank-holidays'
```

```java
WorkingDayCalendar ew = bankHolidays.calendar(Division.ENGLAND_AND_WALES);  // immutable snapshot

ew.isWorkingDay(date);       // Monday to Friday, not a bank holiday
ew.isBankHoliday(date);      // GOV.UK's bank holidays only; weekends are not
ew.shift(date, 5);           // 5 working days on; negative goes back
ew.nextOrSame(date);         // onto a working day: the date, or the next one
ew.previousOrSame(date);
ew.holidays(from, toExclusive);  // List<BankHoliday(division, date, title, notes)>
ew.knownUntil();             // 31 December of GOV.UK's last published year
```

A deadline of so many calendar days that runs to the next working day is
`ew.nextOrSame(date.plusDays(days))`, not `ew.shift(date, days)`.

## Where they come from

- The bank holidays are kept in the `bank_holidays` schema, migrated with the SDK's other library
  schemas (it needs the application's PostgreSQL `DataSource`), and read into memory at startup, so
  lookups never wait on GOV.UK.
- With scheduling enabled (`@EnableScheduling`), each instance checks hourly, and at startup, whether
  the last successful fetch of GOV.UK's feed is a day old. If it is, one instance fetches it for all,
  replacing the stored bank holidays of the years the feed has when they change, and every instance
  reads the store again. Years the feed no longer has are kept, so dates once known stay known. A
  failed fetch, or a feed missing a division or a year, keeps them, is recorded in
  `bank_holidays.fetch_state.last_attempt_error`, and is tried again at the next check.
- Without scheduling the bank holidays are never fetched, and the application logs a warning at
  startup: they run out at the end of the last year in the SDK's copy.
- Before the first fetch they are seeded from a copy of the feed in the SDK, as they are again by a
  newer SDK whose copy reaches a later year than those stored.
- A calendar taken with `calendar(...)` does not change when the bank holidays are refreshed.
- Every method throws `UnknownBankHolidaysException`, a `DateTimeException`, for a date in a year
  GOV.UK has not published, rather than treat it as having none.

| Property | Default |
| --- | --- |
| `ccd.bank-holidays.fetch` | `true`; `false` never fetches, keeping those stored or the SDK's copy |
| `ccd.bank-holidays.url` | `https://www.gov.uk/bank-holidays.json` |

## In tests

An application's own `BankHolidays` bean replaces the auto-configured one, its store and its
refresh, so tests need no database or GOV.UK for it:

- `BankHolidays.bundled()` gives the copy of GOV.UK's feed in this version of the SDK.
- `BankHolidays.of(...)` gives the bank holidays passed, known for the whole years from each
  division's earliest to its latest.
