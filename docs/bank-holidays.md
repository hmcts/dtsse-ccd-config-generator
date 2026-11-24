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
ew.isHoliday(date);
ew.shift(date, 5);           // 5 working days on; negative goes back
ew.nextOrSame(date);         // onto a working day: the date, or the next one
ew.previousOrSame(date);
ew.knownUntil();             // 31 December of GOV.UK's last published year

bankHolidays.holidays(Division.SCOTLAND, from, toExclusive);  // BankHoliday(division, date, title, notes)
```

A deadline of so many calendar days that runs to the next working day is
`ew.nextOrSame(date.plusDays(days))`, not `ew.shift(date, days)`.

## Where they come from

- The bank holidays are kept in the `bank_holidays` schema, migrated with the SDK's other library
  schemas, and read into memory, so lookups never wait on GOV.UK.
- With scheduling enabled (`@EnableScheduling`), each instance checks hourly, and at startup, whether
  the last successful fetch of GOV.UK's feed is a day old. If it is, one instance fetches it for all,
  replacing the stored bank holidays when they change, and every instance reads the store again. A
  failed fetch keeps them, is recorded in `bank_holidays.fetch_state.last_attempt_error`, and is
  tried again at the next check.
- Before the first fetch they are seeded from a copy of the feed in the SDK. A newer SDK's copy
  replaces stored bank holidays it reaches later years than.
- A calendar taken with `calendar(...)` does not change when the bank holidays are refreshed.
- Every method throws `UnknownBankHolidaysException` for a date in a year GOV.UK has not published,
  rather than treat it as having none.

| Property | Default |
| --- | --- |
| `ccd.bank-holidays.url` | `https://www.gov.uk/bank-holidays.json`; blank never fetches, keeping the SDK's copy |
| `ccd.bank-holidays.timeout` | `10s` |
| `ccd.bank-holidays.max-age` | `P1D`: how old the last fetch may be before one is made again |
| `ccd.bank-holidays.refresh.delay` | `PT1H`: how often each instance checks |
| `ccd.bank-holidays.refresh.initial-delay` | `PT0S` |

In tests, `BankHolidays.of(...)` gives bank holidays fixed to the ones passed.
