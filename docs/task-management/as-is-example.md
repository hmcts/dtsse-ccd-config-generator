# As-Is Example: registerNewCase (current WA/Camunda flow)

## Task definition (DMNs in this repo)
- Task type list: `src/main/resources/dmn/wa-task-types-st_cic-criminalinjuriescompensation.dmn`
  - Rule output: `taskTypeId=registerNewCase`, `taskTypeName="Register new case"`.
- Initiation rule: `src/main/resources/dmn/wa-task-initiation-st_cic-criminalinjuriescompensation.dmn`
  - Trigger: `eventId="citizen-cic-submit-dss-application"` and `postEventState="DSS_Submitted"`.
  - Outputs:
    - `taskId=registerNewCase`
    - `name="Register new case"`
    - `delayDuration` empty
    - `workingDaysAllowed=5`
    - `processCategories` empty
    - `workType="applications"`
    - `roleCategory="ADMIN"`
- Configuration rule: `src/main/resources/dmn/wa-task-configuration-st_cic-criminalinjuriescompensation.dmn`
  - Task-specific description:
    - `name="description"`
    - `value="[Case: Edit case](/cases/case-details/${[CASE_REFERENCE]}/trigger/edit-case)"`
    - `canReconfigure=true`
  - Base config rules also populate case name, location, priorities, and due-date defaults for all tasks.
- Permissions rules: `src/main/resources/dmn/wa-task-permissions-st_cic-criminalinjuriescompensation.dmn`
  - `registerNewCase` is included in role rules for:
    - `regional-centre-admin`, `regional-centre-team-leader`
    - `hearing-centre-admin`, `hearing-centre-team-leader`
    - `ctsc`, `ctsc-team-leader`
    - plus the generic `task-supervisor` rule.
- Completion rule: `src/main/resources/dmn/wa-task-completion-st_cic-criminalinjuriescompensation.dmn`
  - `eventId="edit-case"` completes `taskType="registerNewCase"` with `completionMode="Auto"`.
- Cancellation: no explicit `registerNewCase` rule in
  `src/main/resources/dmn/wa-task-cancellation-st_cic-criminalinjuriescompensation.dmn`.

## Deployment to Camunda
- Jenkins uploads DMNs to Camunda using `bin/wa/import-dmn-diagram.sh` (tenant `st_cic`, product `st_cic`).
  - Pipeline entry point: `Jenkinsfile_CNP` → `uploadDmnDiagrams()`.
- BPMN is pulled from `wa-standalone-task-bpmn` and deployed via `bin/wa/import-wa-bpmn-diagram.sh`.
  - Pipeline entry point: `Jenkinsfile_CNP` → `uploadBpmnDiagram()`.

## Runtime execution (current)
1) CCD event emitted → WA case event handler evaluates initiation DMN and sends a Camunda message.
2) Camunda BPMN starts a task process instance (unconfigured task).
3) WA Task Monitor `INITIATION` job finds unconfigured tasks and calls Task Management `/task/{id}/initiation`.
4) Task Management evaluates configuration + permissions DMNs and persists the task.

## Evidence in tests
- `src/functionalTest/java/uk/gov/hmcts/sptribs/wa/WATaskRegisterNewCaseFT.java`
  - Creates case, waits for task creation, verifies role permissions, assigns and completes task.
