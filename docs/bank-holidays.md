# Bank holidays

`sdk/bank-holidays` (`com.github.hmcts:bank-holidays`) gives the UK's bank holidays as
[GOV.UK publishes them](https://www.gov.uk/bank-holidays.json), for England and Wales, Scotland and
Northern Ireland, and working days by them. `task-management` uses it for delay-until working days.

```groovy
implementation 'com.github.hmcts:bank-holidays'
```

```java
bankHolidays.isWorkingDay(Division.ENGLAND_AND_WALES, date);    // Monday to Friday, not a bank holiday
bankHolidays.addWorkingDays(Division.ENGLAND_AND_WALES, date, 5);
bankHolidays.between(Division.SCOTLAND, from, toExclusive);      // BankHoliday(division, date, title, notes)
bankHolidays.knownUntil(Division.NORTHERN_IRELAND);              // 31 December of GOV.UK's last published year
```

## Where they come from

- The bank holidays are kept in the `bank_holidays` schema, migrated with the SDK's other library
  schemas, and read into memory, so lookups never wait on GOV.UK.
- With scheduling enabled (`@EnableScheduling`), each instance fetches GOV.UK's feed at startup and
  every six hours, replacing the stored bank holidays when they change. A failed fetch keeps them,
  and is recorded in `bank_holidays.fetch_state.last_fetch_error`.
- Before the first fetch they are seeded from a copy of the feed in the SDK. A newer SDK's copy
  replaces stored bank holidays it reaches later years than.
- Every method throws `UnknownBankHolidaysException` for a date in a year GOV.UK has not published,
  rather than treat it as having none.

| Property | Default |
| --- | --- |
| `ccd.bank-holidays.url` | `https://www.gov.uk/bank-holidays.json`; blank never fetches, keeping the SDK's copy |
| `ccd.bank-holidays.timeout` | `10s` |
| `ccd.bank-holidays.refresh.delay` | `PT6H` |
| `ccd.bank-holidays.refresh.initial-delay` | `PT0S` |

In tests, `BankHolidays.of(...)` gives bank holidays fixed to the ones passed.
