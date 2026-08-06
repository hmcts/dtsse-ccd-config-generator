create trigger ccd_audit_row_changes
    after insert or update or delete on case_notes
    for each row
    execute function ccd.audit_row_change();
