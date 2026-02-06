## Goal 5: Task Reconfiguration

We need a minimal way for services to reconfigure tasks so the task list stays aligned with case state changes.

### Outcome
- Services can reconfigure tasks for a case
- Task Management keeps the rule evaluation inside the service, not the task API
- Behavior mirrors current WA reconfiguration flows without Camunda

### Minimal API shape
- Reconfigure accepts a `case_id` plus a list of task payloads (`TaskPayload`)
- Returns `200 OK` on success

### Pending Tests

The following e2e test is expected to fail until the `wa-task-management-api` submodule implements the reconfigure endpoint:

- `taskShouldReconfigureViaOutboxPoller` - Tests task reconfiguration via the outbox poller

