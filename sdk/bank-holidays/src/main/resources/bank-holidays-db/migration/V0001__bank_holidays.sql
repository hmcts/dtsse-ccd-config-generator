-- The UK's bank holidays as GOV.UK publishes them (https://www.gov.uk/bank-holidays.json), as last
-- fetched or, before the first fetch, as seeded from the SDK. Replaced as a whole on each change.
create table bank_holidays.bank_holiday (
    division varchar(32) not null
        check (division in ('ENGLAND_AND_WALES', 'SCOTLAND', 'NORTHERN_IRELAND')),
    date date not null,
    title text not null,
    -- For example 'Substitute day'; empty when GOV.UK gives none.
    notes text not null,
    primary key (division, date)
);

-- Where the bank holidays came from and how the last fetch from GOV.UK went. One row.
create table bank_holidays.fetch_state (
    id smallint primary key default 1 check (id = 1),
    source varchar(16) not null check (source in ('GOV_UK', 'SEED')),
    -- When bank_holiday was last replaced.
    updated_at timestamptz not null,
    -- The last successful fetch from GOV.UK; instances fetch again once it is a day old.
    fetched_at timestamptz,
    -- The last fetch any instance started, and its error, null when it succeeded.
    last_attempt_at timestamptz,
    last_attempt_error text
);
