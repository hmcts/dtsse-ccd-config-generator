# Proposed API Spec

## Purpose
Create fully formed tasks directly in Task Management without relying on Camunda or DMN evaluation.
This API-first design is predicated on the caller supplying all mandatory fields.

## Idempotency
- `task_id` forms the idempotency key (scoped to the `case_type_id`).
- Repeating the same request with the same `task_id` and `case_type_id` must return the same task (201 or 200).
- The same `task_id` with a different payload must return `409 Conflict`.

## `POST /task/create`
`POST /task` is already used for task search, so API-first creation is exposed under `/task/create`.
Payload fields use `snake_case`.

```json
{
  "task": {
    "task_id": "9f3e7e8a-1d3b-4e59-8cb0-2d86f630c1c6",
    "type": "registerNewCase",
    "name": "Register new case",
    "title": "Register new case",
    "state": "UNASSIGNED",
    "created": "2025-01-06T10:15:30Z",
    "execution_type": "Case Management Task",
    "case_id": "1234567890123456",
    "case_type_id": "CriminalInjuriesCompensation",
    "case_category": "CIC",
    "case_name": "Smith v Doe",
    "jurisdiction": "ST_CIC",
    "region": "1",
    "location": "336559",
    "work_type": "applications",
    "role_category": "ADMIN",
    "security_classification": "PUBLIC",
    "description": "[Case: Edit case](/cases/case-details/1234567890123456/trigger/edit-case)",
    "due_date_time": "2025-01-10T16:00:00Z",
    "priority_date": "2025-01-10T16:00:00Z",
    "major_priority": 5000,
    "minor_priority": 500,
    "location_name": "Glasgow Tribunals Centre",
    "region_name": "Scotland",
    "task_system": "SELF",
    "additional_properties": {
      "originating_caseworker": "user@example.com"
    },
    "permissions": [
      {
        "role_name": "regional-centre-admin",
        "role_category": "ADMIN",
        "permissions": [
          "Read",
          "Own",
          "Claim",
          "Unclaim",
          "Manage",
          "Complete"
        ],
        "authorisations": [
          "AUTH_1",
          "AUTH_2"
        ],
        "assignment_priority": 1,
        "auto_assignable": false
      }
    ]
  }
}
```

## Task Fields

### task_id
Client-supplied task identifier, unique within the case type. UUID string (lowercase or uppercase hex with hyphens).
Required.

### type
Service-defined task type identifier. String. Required.

### name
User-facing task name used for display in UI lists. String. Required.

### title
Optional display title. String. If omitted, UI should fall back to `name`.

### state
Initial task state. String. Allowed values: `UNASSIGNED`, `ASSIGNED`. Required.

### created
Creation timestamp supplied by the caller. ISO-8601 with offset. Required.

### execution_type
Execution category. String. Allowed values: `Manual`, `Built In`, `Case Management Task`. Required.

### case_id
CCD case reference. String. Required.

### case_type_id
CCD case type identifier. String. Required.

### case_category
Primary case management category for the case. String. Optional.
Value should be the CCD `caseManagementCategory.categoryId` (e.g., `CIC`) or an agreed service code.
Used for filtering, reporting, and routing; Task Management does not derive or validate it beyond non-empty.

### case_name
Human-readable case name. String. Optional.

### jurisdiction
CCD jurisdiction identifier. String. Required.

### region
Case management region identifier from CCD location. String (often numeric). Optional.
Value should match `caseManagementLocation.region`. Provide alongside `location` where possible.
Used for routing and filtering; not derived by Task Management.

### location
Base location identifier from CCD case management location. String (often numeric). Optional.
Value should match `caseManagementLocation.baseLocation` when a specific venue/office applies.
Used for routing and filtering; should be consistent with `region`.

### work_type
Work type identifier. String. Required.
Value should be from the service's work type catalog if one exists.
Used for task grouping, filtering, and assignment policies; keep values stable over time.

### role_category
Role category for the task. String. For example: `ADMIN`, `CTSC`, `LEGAL_OPERATIONS`, `JUDICIAL`. Required.

### security_classification
Security classification. String. Allowed values: `PUBLIC`, `PRIVATE`, `RESTRICTED`. Required.

### description
Task description text. String. Supports markdown-style links. Optional.

### due_date_time
Target due date/time. ISO-8601 with offset. Required.

### priority_date
Business priority date/time. ISO-8601 with offset. Optional.
Represents when the task should rise in priority (e.g., SLA start or escalation point).
Used alongside `major_priority`/`minor_priority` for ordering; earlier dates indicate higher urgency.

### major_priority
Major priority level. Integer. Optional. Higher values indicate higher priority.
Use a consistent scale within the service (e.g., 0-10000).

### minor_priority
Minor priority level. Integer. Optional. Used as a tie-breaker within the same major priority.
Higher values indicate higher priority. Use a smaller, consistent scale (e.g., 0-1000).

### location_name
Location display name. String. Optional.

### region_name
Region display name. String. Optional.

### task_system
Task origin system. String. Allowed values: `SELF`, `CTSC`. Optional (defaults to `SELF`).

### additional_properties
Free-form string map for service-specific metadata. Optional.

### permissions
Array of permission entries. Required. Each entry represents one access-control role for the task.

### Supported permission values:
`Read`, `Own`, `Execute`, `Manage`, `Cancel`, `Refer`, `Complete`, `CompleteOwn`, `CancelOwn`,
`Claim`, `Unclaim`, `Assign`, `Unassign`, `UnclaimAssign`, `UnassignClaim`, `UnassignAssign`.

## Response
`201 Created` with a minimal `TaskResource` representation.
`200 OK` if the task already exists with the same payload.

Example:
```json
{
  "task_id": "9f3e7e8a-1d3b-4e59-8cb0-2d86f630c1c6",
  "task_name": "Register new case",
  "task_type": "registerNewCase",
  "state": "UNASSIGNED",
  "case_id": "1234567890123456",
  "case_type_id": "CriminalInjuriesCompensation",
  "jurisdiction": "ST_CIC",
  "created": "2025-01-06T10:15:30Z"
}
```

## Error Codes
- `400 Bad Request`: validation errors.
- `401 Unauthorized` / `403 Forbidden`: auth failures.
- `409 Conflict`: `task_id` reused with different payload.

## Notes
- This endpoint bypasses Camunda and DMN evaluation.
- TODO: Completion can use the existing `POST /task/{id}/complete` endpoint?
