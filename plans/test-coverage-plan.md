# Task Management PoC Test Coverage Plan

This plan covers the missing test coverage for the `wa-task-management-api` `decentralisation-poc` branch as identified by `./gradlew :wa-task-management-api:jacocoTestReport`.

The intent is to add meaningful behavioural coverage in the same style as the existing repo tests:

- Use Spring `MockMvc` integration tests for API contract and persistence behaviour.
- Use service unit tests for orchestration, branching, and collaborator interactions.
- Use mapper unit tests for request-to-entity mapping and validation-adjacent enum handling.
- Prefer extending existing test classes where that keeps the test focused and readable.

## Current Coverage Gaps

The report shows the new API-first read path is covered, but the mutating API-first paths are not.

Highest-value uncovered areas:

- `TaskPocController`
  - `POST /tasks`
  - `POST /tasks/terminate`
  - `PUT /tasks/reconfigure`
  - negative filter validation branches on `GET /tasks`
- `TaskManagementService`
  - `addTask`
  - bulk `terminateTasks`
  - `isTaskAlreadyTerminated`
  - `updateTaskState`
  - API-first `reconfigureTasks`
  - `task.isCamundaTask()` false branches in claim, unclaim, cancel, complete, and terminate flows
- `CFTTaskMapper`
  - `mapToApiFirstTaskResource`
  - API-first `mapPermissions`
  - `mapToTaskResourceForReconfigure`
- Small parser branches
  - `ExecutionType.fromJson`
  - `PermissionTypes.fromJson`
- `CFTTaskDatabaseService.findAllBy`
  - case-id-only and task-types-only filter combinations

## Controller Integration Tests

Add tests alongside `GetTasksControllerTest`, or split into nearby endpoint-specific classes if the file becomes too broad.

Use the same pattern already present there:

- `@SpringBootTest`
- `@ActiveProfiles("integration")`
- `@AutoConfigureMockMvc(addFilters = false)`
- `@MockitoBean` for external clients and access-control services
- direct repository/database setup for persisted task state
- `MockMvc` assertions on HTTP status, content type, JSON body, and persisted effects

Cover `POST /tasks`:

- authorised request creates an API-first task and returns `201 Created`
- response includes the saved task fields that matter to clients, including `externalTaskId`, case fields, work type, permissions, dates, priorities, and additional properties
- created task is persisted with `external_task_id`, `UNCONFIGURED` initial state, mapped task roles, and no Camunda dependency
- index update is triggered by the endpoint after creation
- forbidden response when `ClientAccessControlService.hasExclusiveAccess` is false
- duplicate `(external_task_id, case_type_id)` returns the problem response wired through `TaskSecondaryKeyConflictException`

Cover `POST /tasks/terminate`:

- authorised cancel request returns `204 No Content`
- authorised complete request returns `204 No Content`
- active tasks move through the expected intermediate state handling and end as `TERMINATED`
- cancellation sets termination reason `deleted`
- completion sets termination reason `completed`
- already terminal or opposite intermediate states are skipped without request failure
- API-first tasks do not call Camunda deletion
- forbidden response when exclusive access is not granted

Cover `PUT /tasks/reconfigure`:

- authorised request returns `200 OK` and the successfully reconfigured task set
- only `ASSIGNED` and `UNASSIGNED` tasks are reconfigured
- missing, `TERMINATED`, `CANCELLED`, or `COMPLETED` tasks are skipped
- persisted task fields are replaced from the request payload, including role resources, work type, dates, priorities, next hearing data, and additional properties
- mandatory-field validation is exercised on the mapped entity
- re-auto-assignment is invoked and the saved task is returned
- forbidden response when exclusive access is not granted

Extend `GET /tasks` coverage:

- blank `case_id` returns bad request
- empty `task_types` returns bad request
- `task_types` containing a blank item returns bad request
- case-id-only search returns matching tasks
- task-types-only search returns matching tasks

## Service Unit Tests

Extend `TaskManagementServiceUnitTest` and `services/taskmanagementservicetests/TerminateTaskTest` rather than introducing a new style of tests.

Cover `addTask`:

- maps the request using `CFTTaskMapper`
- runs auto-assignment before save
- saves and flushes the entity manager
- returns the saved task
- converts the idempotency unique-index `ConstraintViolationException` into `TaskSecondaryKeyConflictException`
- rethrows non-idempotency constraint violations unchanged

Cover bulk `terminateTasks`:

- de-duplicates repeated task IDs before processing
- maps `CANCEL` to termination reason `deleted`
- maps `COMPLETE` to termination reason `completed`
- applies the intermediate `CANCELLED` or `COMPLETED` state before final termination
- skips tasks already in `TERMINATED`
- skips `CANCELLED`/`COMPLETED` tasks according to the current `isTaskAlreadyTerminated` rules
- saves updated tasks and delegates to existing `terminateTask`

Cover API-first `reconfigureTasks`:

- locks only tasks in `ASSIGNED` or `UNASSIGNED`
- skips missing or non-reconfigurable tasks
- maps request payload onto the existing `TaskResource`
- validates mandatory fields after mapping
- sets `lastReconfigurationTime`
- runs `reAutoAssignCFTTask`
- saves and returns each successful task in `TaskReconfigureResponse`

Cover API-first lifecycle branches:

- claim on API-first task saves assignment state and does not call `camundaService.assignTask`
- unclaim on API-first task clears assignee and does not call `camundaService.unclaimTask`
- cancel on API-first task saves CFT state/action data and does not call `camundaService.cancelTask`
- complete on API-first task saves CFT state/action data and does not call Camunda completion
- terminate on API-first task saves CFT termination data and does not call `camundaService.deleteCftTaskState`

## Mapper And Parser Unit Tests

Extend `CFTTaskMapperTest`.

Cover `mapToApiFirstTaskResource`:

- maps all required create payload fields onto `TaskResource`
- sets generated internal `taskId`
- sets `externalTaskId`
- defaults title to task name when request title is null
- maps execution type from value, name, and enum name forms
- maps task system and security classification from request enum values
- maps null additional properties to an empty map
- maps non-null additional properties to string values
- maps every API-first permission flag into `TaskRoleResource`
- throws for an invalid permission type

Cover `mapToTaskResourceForReconfigure`:

- replaces reconfigurable fields on an existing task
- replaces task roles from payload permissions
- maps null additional properties to an empty map
- maps non-null additional properties to string values
- preserves fields that are intentionally not present on the reconfigure payload

Add small enum/parser tests:

- `ExecutionType.fromJson` accepts `value`, display `name`, and enum constant name, case-insensitively
- `ExecutionType.fromJson` throws on an unknown value
- `PermissionTypes.fromJson` accepts a known API value
- `PermissionTypes.fromJson` throws on an unknown value

## Verification

Run the focused tests while developing:

```bash
./gradlew :wa-task-management-api:test --tests '*CFTTaskMapperTest' --tests '*TaskManagementServiceUnitTest' --tests '*TerminateTaskTest'
./gradlew :wa-task-management-api:integration --tests '*GetTasksControllerTest'
```

Then run the full coverage command:

```bash
./gradlew :wa-task-management-api:jacocoTestReport
```

Review `wa-task-management-api/build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report to confirm the changed API-first paths are covered. The target is not a synthetic percentage; the useful outcome is that the new controller, mapper, and service methods are covered by behaviour-level tests and the remaining uncovered lines are either existing legacy branches or deliberately low-value generated/simple code.
