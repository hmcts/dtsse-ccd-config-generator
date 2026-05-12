alter type ccd.task_outbox_status add value if not exists 'WAITING' after 'NEW';
