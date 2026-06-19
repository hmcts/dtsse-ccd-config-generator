# Task-Level Authorisation Plan

## Summary

Implement service-to-case-type authorisation for the production Task Management SDK APIs. Use the document AM idea of YAML-backed service policy, but keep the Task Management model narrower: a service is allowed to operate on configured CCD case types, and every SDK endpoint must declare or derive a single `case_type_id` scope.

## API And Config Changes

- Add required `case_type_id` to:
  - `GET /tasks?case_id=...&task_types=...&case_type_id=...`
  - `POST /tasks/terminate` request body
  - `PUT /tasks/reconfigure` request body
- Keep `POST /tasks` using `task.case_type_id`, already required.
- Add YAML config under `config`, using a typed `@ConfigurationProperties` model:

  ```yaml
  config:
    service-case-type-access:
      sptribs_case_api:
        - CriminalInjuriesCompensation
      nfdiv_case_api:
        - NFD
  ```

- Fail closed:
  - service not in `exclusiveAccessClients`: `403`
  - service has no case-type access entry: `403`
  - requested `case_type_id` not in service scope: `403`
- Do not add document AM-style operation permissions, jurisdiction checks, or wildcard support for v1. The production requirement is case-type ownership, and wildcard/admin access would weaken that boundary.

## Implementation Changes

- Add a small authorisation service beside `ClientAccessControlService` that:
  - resolves the S2S service name once from `ServiceAuthorization`
  - checks exclusive access
  - loads allowed case types
  - validates the requested `case_type_id`
  - returns a caller scope object containing `serviceName` and `caseTypeId`
- Update the Task Management SDK endpoints in `TaskPocController` to require the caller scope before calling `TaskManagementService`.
- Update service/database calls:
  - `getTasks(caseId, taskTypes, caseTypeId)` filters by `caseTypeId` at query time.
  - `addTask(task, caseTypeId)` checks payload case type before saving.
  - `terminateTasks(request, caseTypeId)` first loads/locks all targeted tasks, verifies every stored task has the requested case type, then mutates.
  - `reconfigureTasks(request, caseTypeId)` follows the same all-targets-authorised-before-mutation rule.
- Update SDK models and Feign client:
  - `getTasks(String caseId, String caseTypeId, List<String> taskTypes)`
  - `TaskTerminationRequest(String action, String caseTypeId, List<String> taskIds)`
  - `TaskReconfigureRequest(String caseTypeId, List<TaskReconfigurePayload> tasks)`
  - Use existing outbox `caseType` values to populate `case_type_id`; consider renaming Java fields to `caseTypeId` for consistency if blast radius is acceptable.

## Test Plan

- Unit test the new authorisation service:
  - allowed service and allowed case type succeeds
  - non-exclusive service fails
  - missing config fails
  - unlisted case type fails
  - blank/null case type fails validation
- Controller/integration tests for all four SDK endpoints:
  - in-scope service can create, query, terminate, and reconfigure
  - out-of-scope service gets `403`
  - `GET /tasks` never returns tasks from another case type
  - terminate/reconfigure mixed batches fail as a whole and do not partially update tasks
  - stored task case type mismatch returns `403`
- SDK tests:
  - Feign/query/body serialization includes `case_type_id`
  - outbox termination and reconfiguration pass the trigger case type through to Task Management
  - SDK validation rejects missing case type before enqueueing/sending

## Assumptions

- Production API compatibility can break for these new SDK endpoints.
- Authorisation is case-type based only for v1.
- Each terminate/reconfigure request operates on one case type; mixed-case-type batches require separate requests.
- Existing non-SDK/user task APIs keep their current role-assignment based authorisation.
