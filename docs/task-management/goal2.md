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

API details: [Task termination](api/task-termination.md).
