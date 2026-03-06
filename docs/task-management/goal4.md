## Goal 4: Task Querying (S2S-only)

We need a task query endpoint for API-first integrations that matches the existing
`TaskManagementApiClient.searchTasks` response contract but does not require a user `Authorization` bearer token.

### Outcome
- Services can search tasks using the same `SearchTaskRequest` payload used today.
- Response shape remains compatible with current SDK models (`TaskSearchResponse` and `TaskPayload`).
- Endpoint access is locked down by service-to-service authentication (`ServiceAuthorization`) rather than user auth.

### Minimal API shape
- Accept the a `case_id` and list of `task_types`.
- Return `200 OK` with task list payload compatible with the current `TaskSearchResponse` mapping.
- Do not require the `Authorization` request header for this API-first search path.

### Why this goal
- API-first task termination and reconfiguration flows search tasks by `case_id` + `task_type` before follow-up actions.
- In these service-driven flows, requiring an IdAM user token is unnecessary coupling; s2s auth is sufficient.

### Pending Tests

N/A - the definition of done will be the successful unblocking of Special Tribs' PR to integrate this feature.
