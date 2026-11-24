-- The UK's bank holidays as GOV.UK publishes them (https://www.gov.uk/bank-holidays.json), as last
-- fetched or, before the first fetch, as seeded from the SDK. A fetch replaces the years it has.
create table bank_holidays.bank_holiday (
    division varchar(32) not null
        check (division in ('ENGLAND_AND_WALES', 'SCOTLAND', 'NORTHERN_IRELAND')),
    date date not null,
    title text not null,
    -- For example 'Substitute day'; empty when GOV.UK gives none.
    notes text not null,
    primary key (division, date)
);

-- How fetching from GOV.UK is going. One row.
create table bank_holidays.fetch_state (
    id smallint primary key default 1 check (id = 1),
    -- The last successful fetch; instances fetch again once it is a day old.
    fetched_at timestamptz,
    -- The last fetch any instance started, and its error, null when it succeeded.
    last_attempt_at timestamptz,
    last_attempt_error text
);

insert into bank_holidays.fetch_state default values;
