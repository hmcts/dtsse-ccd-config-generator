# Bank holidays

`sdk/bank-holidays` (`com.github.hmcts:bank-holidays`) gives the UK's bank holidays as
[GOV.UK publishes them](https://www.gov.uk/bank-holidays.json), for England and Wales, Scotland and
Northern Ireland.

```groovy
implementation 'com.github.hmcts:bank-holidays'
```

```java
bankHolidays.isBankHoliday(Division.ENGLAND_AND_WALES, date);
bankHolidays.holidays(Division.ENGLAND_AND_WALES);   // List<BankHoliday(division, date, title, notes)>, earliest first
```

`isBankHoliday` throws `UnknownBankHolidaysException` for a date in a year with no bank holidays
known, rather than treat it as having none.

## Where they come from

- They are kept in the `bank_holidays` schema, migrated with the SDK's other library schemas (it
  needs the application's PostgreSQL `DataSource`), and read into memory at startup, so lookups
  never wait on GOV.UK.
- An empty store is seeded from a copy of GOV.UK's feed in the SDK.
- With scheduling enabled (`@EnableScheduling`), each instance fetches GOV.UK's feed at startup and
  daily. A fetch replaces the years it has and keeps the others; a failed fetch keeps them all.
- `ccd.bank-holidays.url` sets the feed's address.

## In tests

An application's own `BankHolidays` bean replaces the auto-configured one and its fetch:
`BankHolidays.bundled()` gives the SDK's copy of GOV.UK's feed, and `BankHolidays.of(...)` the bank
holidays passed.
