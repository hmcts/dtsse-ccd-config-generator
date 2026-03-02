## Goal 6: Task Search for API-First Flows (S2S-only)

We need a task search endpoint for API-first integrations that matches the existing
`TaskManagementApiClient.searchTasks` contract but does not require a user `Authorization` bearer token.

### Outcome
- Services can search tasks using the same `SearchTaskRequest` payload used today.
- Request/response shape remains compatible with current SDK models (`TaskSearchResponse` and `TaskPayload`).
- Endpoint access is locked down by service-to-service authentication (`ServiceAuthorization`) rather than user auth.

### Minimal API shape
- Reuse the existing task search route semantics used by the SDK (`POST /task`).
- Accept the existing search body (`search_parameters`, optional sorting, `request_context`).
- Return `200 OK` with task list payload compatible with the current `TaskSearchResponse` mapping.
- Do not require the `Authorization` request header for this API-first search path.

### Why this goal
- API-first task termination and reconfiguration flows search tasks by `case_id` + `task_type` before follow-up actions.
- In these service-driven flows, requiring an IdAM user token is unnecessary coupling; s2s auth is sufficient.

### Pending Tests

N/A - the definition of done will be the successful unblocking of Special Tribs' PR to integrate this feature.
