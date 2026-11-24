-- The UK's bank holidays as GOV.UK publishes them (https://www.gov.uk/bank-holidays.json). A fetch
-- replaces the years it has and leaves the others, so years once stored stay.
create table bank_holidays.bank_holiday (
    division varchar(32) not null,
    date date not null,
    title text not null,
    -- For example 'Substitute day'; empty when GOV.UK gives none.
    notes text not null,
    primary key (division, date)
);
