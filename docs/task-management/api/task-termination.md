# Proposed API Spec: Task Termination (Cancel + Complete)

## Purpose
Terminate tasks directly in Task Management without Camunda or DMN evaluation.
The service decides which tasks to terminate based on its own rules and case events.

## Idempotency
- Termination is idempotent per `task_id` + `action`.
- Repeating the same request for an already-terminated task returns `204 No Content`.

## `POST /tasks/terminate`
Bulk termination is exposed under `/tasks/terminate` so a single case event can close multiple tasks.
Payload fields use `snake_case`.

### Request
```json
{
  "action": "Cancel",
  "task_ids": [
    "b49d2f4f-1d9e-4c2a-9f0a-8b0d9b2c90aa",
    "e99b6b54-2b51-4d7c-93c1-9c6c1b7f9014"
  ]
}
```

### Request Fields

#### action
Termination action. String. Allowed values: `Cancel`, `Complete`. Required.

#### task_ids
List of task IDs to terminate. Array of UUID strings. Required.
Values must be unique; an empty list returns `400 Bad Request`.

## Response
`204 No Content` with an empty body.

## Error Codes
- `400 Bad Request`: invalid action, empty list, invalid task ID format.
- `401 Unauthorized` / `403 Forbidden`: auth failures.
- `404 Not Found`: one or more task IDs do not exist.

## Notes
- Task Management does not evaluate rules; the service computes the task IDs to terminate.
- Cancellation groups (process category identifiers) are resolved in service code before calling this endpoint.
