# Proposed API Spec

## Purpose
Create fully formed tasks directly in Task Management without relying on Camunda or DMN evaluation.
This API-first design is predicated on the caller supplying all mandatory fields.

## Idempotency
- `taskId` forms the idempotency key (scoped to the `caseTypeId`).
- Repeating the same request with the same `taskId` and `caseTypeId` must return the same task (201 or 200).
- The same `taskId` with a different payload must return `409 Conflict`.

## `POST /task`
```json
{
  "task": {
    "taskId": "9f3e7e8a-1d3b-4e59-8cb0-2d86f630c1c6",
    "type": "registerNewCase",
    "name": "Register new case",
    "title": "Register new case",
    "state": "UNASSIGNED",
    "created": "2025-01-06T10:15:30Z",
    "executionType": "Case Management Task",
    "caseId": "1234567890123456",
    "caseTypeId": "CriminalInjuriesCompensation",
    "caseCategory": "CIC",
    "caseName": "Smith v Doe",
    "jurisdiction": "ST_CIC",
    "region": "1",
    "location": "336559",
    "workType": "applications",
    "roleCategory": "ADMIN",
    "securityClassification": "PUBLIC",
    "description": "[Case: Edit case](/cases/case-details/1234567890123456/trigger/edit-case)",
    "dueDateTime": "2025-01-10T16:00:00Z",
    "priorityDate": "2025-01-10T16:00:00Z",
    "majorPriority": 5000,
    "minorPriority": 500,
    "locationName": "Glasgow Tribunals Centre",
    "regionName": "Scotland",
    "taskSystem": "SELF",
    "additionalProperties": {
      "originatingCaseworker": "user@example.com"
    },
    "permissions": [
      {
        "roleName": "regional-centre-admin",
        "roleCategory": "ADMIN",
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
        "assignmentPriority": 1,
        "autoAssignable": false
      }
    ]
  }
}
```

## Task Fields

### taskId
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

### executionType
Execution category. String. Allowed values: `Manual`, `Built In`, `Case Management Task`. Required.

### caseId
CCD case reference. String. Required.

### caseTypeId
CCD case type identifier. String. Required.

### caseCategory
Primary case management category for the case. String. Optional.
Value should be the CCD `caseManagementCategory.categoryId` (e.g., `CIC`) or an agreed service code.
Used for filtering, reporting, and routing; Task Management does not derive or validate it beyond non-empty.

### caseName
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

### workType
Work type identifier. String. Required.
Value should be from the service's work type catalog if one exists.
Used for task grouping, filtering, and assignment policies; keep values stable over time.

### roleCategory
Role category for the task. String. For example: `ADMIN`, `CTSC`, `LEGAL_OPERATIONS`, `JUDICIAL`. Required.

### securityClassification
Security classification. String. Allowed values: `PUBLIC`, `PRIVATE`, `RESTRICTED`. Required.

### description
Task description text. String. Supports markdown-style links. Optional.

### priorityDate
Business priority date/time. ISO-8601 with offset. Optional.
Represents when the task should rise in priority (e.g., SLA start or escalation point).
Used alongside `majorPriority`/`minorPriority` for ordering; earlier dates indicate higher urgency.

### majorPriority
Major priority level. Integer. Optional. Higher values indicate higher priority.
Use a consistent scale within the service (e.g., 0-10000).

### minorPriority
Minor priority level. Integer. Optional. Used as a tie-breaker within the same major priority.
Higher values indicate higher priority. Use a smaller, consistent scale (e.g., 0-1000).

### locationName
Location display name. String. Optional.

### regionName
Region display name. String. Optional.

### permissions
Array of permission entries. Required. Each entry represents one access-control role for the task.

### Supported permission values:
`Read`, `Own`, `Execute`, `Manage`, `Cancel`, `Refer`, `Complete`, `CompleteOwn`, `CancelOwn`,
`Claim`, `Unclaim`, `Assign`, `Unassign`, `UnclaimAssign`, `UnassignClaim`, `UnassignAssign`.

## Response
`201 Created` with a `TaskResource` representation.

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
- `409 Conflict`: `taskId` reused with different payload.

## Notes
- This endpoint bypasses Camunda and DMN evaluation.
- TODO: Completion can use the existing `POST /task/{id}/complete` endpoint?
