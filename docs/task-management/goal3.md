# Goal 3: Task Claiming

We need a minimal way for users to claim tasks created via the API-first flow so they can start work on cases
without relying on Camunda or WA task orchestration.

## Outcome
- Users can claim API-first tasks via Task Management.
- Existing Work Allocation UI flows can claim tasks created by services.
- Behavior matches current WA expectations; the service does not decide assignment rules.

## Minimal API shape
- We will reuse the existing Task Management claim endpoint.
- Claim a single task via `POST /task/{id}/claim`.
- Request body can be empty (`{}`) with `Content-Type: application/json`.

Reference (current Task Management controller):
[TaskActionsController#L148](https://github.com/hmcts/wa-task-management-api/blob/decentralisation-poc/src/main/java/uk/gov/hmcts/reform/wataskmanagementapi/controllers/TaskActionsController.java#L148)

## How to run the red tests locally

Run the tests:
```bash
./gradlew -i e2e:cftlibTest
```

The failing test is:
- `apiFirstTasksIsClaimable`
