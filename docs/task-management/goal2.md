# Goal 2: Task Termination (Cancel + Complete)

We need a minimal way for services to end tasks so users do not see stale or irrelevant work items; when case events happen, the right tasks are cancelled or completed.

## Outcome
- Services can cancel or complete tasks
- Task Management stays simple; the service owns the rules that decide what to terminate.
- Behavior matches what services have today under WA/DMN, but in code and without Camunda.

## Minimal API shape
- Terminate accepts an `action` (`Cancel` or `Complete`) plus a list of `task_ids`.

## Notes / out of scope
- Reconfigure (cancel + recreate) is a follow-up goal.

## Pending Tests

The following e2e tests are expected to fail until the `wa-task-management-api` submodule implements the termination endpoint:

- `taskShouldCompleteViaOutboxPoller` - Tests task completion via the outbox poller
- `taskShouldCancelViaOutboxPoller` - Tests task cancellation via the outbox poller

These tests create a task using `API_FIRST_TASK_EVENT_ID`, then attempt to complete/cancel it using `API_FIRST_TASK_COMPLETE_EVENT_ID` or `API_FIRST_TASK_CANCEL_EVENT_ID`. The task creation succeeds but the termination fails because the terminate endpoint is not yet implemented.

API details: [Task termination](api/task-termination.md).
