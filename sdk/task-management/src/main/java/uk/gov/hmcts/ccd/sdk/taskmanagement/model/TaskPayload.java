package uk.gov.hmcts.ccd.sdk.taskmanagement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Immutable SDK representation of the API-first task-management {@code /tasks} payload shape.
 *
 * <p>JSON is serialized in snake_case, null properties are omitted, and unknown properties are
 * ignored so the SDK stays tolerant of additive API changes.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskPayload {
  /**
   * Client-supplied business identifier that forms part of task-management's create idempotency key with
   * {@link #caseTypeId}.
   *
   * <p>This is distinct from the generated task-management task {@link #id} and helps services correlate the
   * task-management task with their own domain identifier.
   *
   * <p>Example: {@code 51ce7930-2946-43c3-a455-a7312195c704}.
   */
  String externalTaskId;

  /**
   * task-management-generated task identifier.
   *
   * <p>This value is returned by create and search operations and is the identifier used for later
   * task operations such as reconfiguration or termination.
   *
   * <p>Example: {@code 47b42110-9a6b-439c-82f0-c83c8c84b549}.
   */
  String id;

  /**
   * Conceptual task type used by task-management to group related business tasks.
   *
   * <p>Example: {@code reviewRule27RequestJudge}.
   */
  String type;

  /**
   * Task name from the process model or initiating service.
   *
   * <p>Example: {@code Review Rule 27 request - Judge}.
   */
  String name;

  /**
   * User-facing task title.
   *
   * <p>Example: {@code Review Rule 27 request - Judge}.
   */
  String title;

  /**
   * Identifier of the currently assigned user, typically an IDAM user id.
   *
   * <p>Example: {@code 125285bd-b97e-44bc-829c-fd32ee984039}.
   */
  String assignee;

  /**
   * Timestamp at which the task task-managements created in task-management.
   *
   * <p>Example persisted value: {@code 2026-04-10 12:47:45.228}.
   */
  OffsetDateTime created;

  /**
   * Execution type code describing how the task should be carried out.
   *
   * <p>Example: {@code CASE_EVENT}.
   */
  String executionType;

  /**
   * CCD case reference associated with the task.
   *
   * <p>Example: {@code 1765554753478659}.
   */
  String caseId;

  /**
   * CCD case type identifier associated with the task.
   *
   * <p>Example: {@code CriminalInjuriesCompensation}.
   */
  String caseTypeId;

  /**
   * Service-defined case category shown in task list responses.
   *
   * <p>Example: {@code Criminal Injuries Compensation}.
   */
  String caseCategory;

  /**
   * Human-readable case name shown in task list responses.
   *
   * <p>Example: {@code FirstName LastName}.
   */
  String caseName;

  /**
   * Jurisdiction that owns the case and task.
   *
   * <p>Example: {@code ST_CIC}.
   */
  String jurisdiction;

  /**
   * Region identifier used by task-management routing and task list filtering.
   *
   * <p>Example: {@code 11}.
   */
  String region;

  /**
   * Physical location identifier, typically an ePims-style location id.
   *
   * <p>Example: {@code 366559}.
   */
  String location;

  /**
   * Work type identifier supplied when creating a task.
   *
   * <p>Example: {@code decision_making_work}.
   */
  String workType;

  /**
   * Role category used by task-management authorisation and assignment rules.
   *
   * <p>Example: {@code JUDICIAL}.
   */
  String roleCategory;

  /**
   * Task security classification, for example {@code PUBLIC}, {@code PRIVATE}, or
   * {@code RESTRICTED}.
   *
   * <p>Example: {@code PUBLIC}.
   */
  String securityClassification;

  /**
   * Free-text guidance telling a user what the task is about or what to do next.
   *
   * <p>Example exported values are typically case-event links such as
   * {@code [Orders: Create and send order](...)}.
   */
  String description;

  /**
   * Due date-time persisted by task-management for the task.
   *
   * <p>Example persisted value: {@code 2026-04-15 12:47:45.228}.
   */
  OffsetDateTime dueDateTime;

  /**
   * Timestamp used by task-management's task search ordering.
   *
   * <p>For API-first create and reconfigure requests, this value is stored on the task and later
   * used by task-management task search as the date-based part of the default ordering after
   * {@link #majorPriority} and before {@link #minorPriority}. When omitted on task creation, task-management
   * defaults this value to {@link #dueDateTime}.
   *
   * <p>Example persisted value: {@code 2026-04-10 12:47:45.228}.
   */
  OffsetDateTime priorityDate;

  /**
   * Primary numeric priority used by task-management's task search ordering.
   *
   * <p>For API-first create and reconfigure requests, this value is stored on the task and later
   * used by task-management task search as the coarse-grained ordering key. Lower values sort ahead of higher
   * values and are applied before {@link #priorityDate} and {@link #minorPriority}.
   *
   * <p>Example: {@code 5000}.
   */
  Integer majorPriority;

  /**
   * Secondary numeric priority used by task-management's task search ordering.
   *
   * <p>For API-first create and reconfigure requests, this value is stored on the task and later
   * used by task-management task search to break ties after {@link #majorPriority} and {@link #priorityDate}.
   * Lower values sort ahead of higher values.
   *
   * <p>Example: {@code 500}.
   */
  Integer minorPriority;

  /**
   * Human-readable location label for display in task list UIs.
   *
   * <p>Example: {@code Glasgow Tribunals Centre}.
   */
  String locationName;

  /**
   * Human-readable region label for display in task list UIs.
   */
  String regionName;

  /**
   * Code indicating which platform owns the task.
   *
   * <p>Example: {@code SELF}.
   */
  String taskSystem;

  /**
   * Service-specific additional attributes persisted as {@code additional_properties}.
   *
   * <p>The SDK accepts arbitrary values for request construction. Task-management normalises values to strings
   * when persisting them.
   *
   * <p>Example exported values include {@code {}} and
   * {@code {"messageIdentifier": "38e5a4eb-70cd-4cd2-a801-d765ca1f3a05"}}.
   */
  Map<String, Object> additionalProperties;

  /**
   * Task role permissions associated with the task.
   *
   * <p>This list is part of the API-first create and reconfigure request contract. Valid
   * permission names come from task-management's {@code PermissionTypes} enum, for example {@code Read},
   * {@code Own}, and {@code Execute}.
   */
  @JsonDeserialize(using = TaskPermissionListDeserializer.class)
  List<TaskPermission> permissions;

  /**
   * Date-time of the next hearing associated with the task, when applicable.
   *
   * <p>Example: {@code 2026-04-08 09:00:00.000}.
   */
  OffsetDateTime nextHearingDate;

  /**
   * Identifier of the next hearing associated with the task, when applicable.
   *
   * <p>Example: {@code 2000017924}.
   */
  String nextHearingId;

  /**
   * {@code GET /tasks} response field corresponding to {@link #dueDateTime}.
   *
   * <p>Example: {@code 2026-04-15 12:47:45.228}.
   */
  OffsetDateTime dueDate;

  /**
   * {@code GET /tasks} response field corresponding to {@link #title}.
   *
   * <p>Example: {@code Review Rule 27 request - Judge}.
   */
  String taskTitle;

  /**
   * {@code GET /tasks} response field corresponding to {@link #workType}.
   *
   * <p>Example: {@code decision_making_work}.
   */
  String workTypeId;
}
