# Milestone 1: API-First Register New Case Task

## Goal
Deliver a single task end-to-end using API-first creation: `registerNewCase`. This proves that task logic can live in Java and the Task Management API can accept a fully formed task without Camunda/DMN evaluation for this case type.

## Scope
- Task: `registerNewCase`
- Event trigger: `citizen-cic-submit-dss-application` resulting in `DSS_Submitted`
- Completion trigger: `edit-case`
- Target environment: preview or AAT only (feature-flagged)

## Functional Spec 
- When a citizen submits a CIC case (event `citizen-cic-submit-dss-application`) and the case transitions to `DSS_Submitted`, create a task.
- The task is named “Register new case” and has type `registerNewCase`.
- Work type is `applications`, role category is `ADMIN`.
- Description is a link to the case edit event.
- Due date is 5 working days after the event time.
- Default priorities and location/region are applied when not present on the case.
- Permissions include regional/hearing centre admin + team leader, CTSC + CTSC team leader, and task supervisor.
- The task completes when the case is edited (`edit-case` event).
- API-first assumes the service provides all mandatory task fields; Task Management does not derive defaults.

## Definition (current reference)
- Initiation DMN rule: `src/main/resources/dmn/wa-task-initiation-st_cic-criminalinjuriescompensation.dmn`
- Configuration DMN rule: `src/main/resources/dmn/wa-task-configuration-st_cic-criminalinjuriescompensation.dmn`
- Permissions DMN rules: `src/main/resources/dmn/wa-task-permissions-st_cic-criminalinjuriescompensation.dmn`
- Completion DMN rule: `src/main/resources/dmn/wa-task-completion-st_cic-criminalinjuriescompensation.dmn`
- Task type DMN: `src/main/resources/dmn/wa-task-types-st_cic-criminalinjuriescompensation.dmn`

## API-First Implementation Plan
1) Add Java rule implementation for `registerNewCase` that builds a full task payload.
2) Add Task Management API client with a `POST /task/create` (new endpoint) call.
3) Add completion call on `edit-case`.
4) Guard with feature flag (API-first on/off).
5) Write unit tests for payload parity + contract test for the API.

## Acceptance Criteria
- Task appears in Task Management for a new citizen submission.
- Permissions reflect expected roles.
- Due date is 5 working days after submission.
- Task is completed after `edit-case`.
- Legacy path remains unaffected when feature flag is off.
