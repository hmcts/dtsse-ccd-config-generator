-- Rebuild case_revision per case to reflect event order before enforcing uniqueness.
with ordered_events as (
    select
        id,
        row_number() over (
            partition by case_data_id
            order by id asc
        ) as ordinal
    from ccd.case_event
)
update ccd.case_event ce
set case_revision = oe.ordinal
from ordered_events oe
where ce.id = oe.id;

-- Align case_data.case_revision with the highest event revision per case.
with latest_revision as (
    select case_data_id, max(case_revision) as max_revision
    from ccd.case_event
    group by case_data_id
)
update ccd.case_data cd
set case_revision = lr.max_revision
from latest_revision lr
where cd.id = lr.case_data_id
  and cd.case_revision is distinct from lr.max_revision;
