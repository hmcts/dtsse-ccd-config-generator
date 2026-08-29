import { initAll } from "govuk-frontend";
import { type Node as ProseMirrorNode } from "prosemirror-model";

import { createOrderBuilder } from "./builder.js";
import { createOrderEditor } from "./client.js";
import "./application.scss";

initAll();

type PartyKind = "claimant" | "defendant";

type AttendanceChoice =
  | "counsel"
  | "solicitor"
  | "solicitors-agent"
  | "housing-officer"
  | "duty-adviser"
  | "in-person"
  | "letter"
  | "not-present";

interface Attendance {
  partyKind: PartyKind;
  partyNumber: number;
  choice: AttendanceChoice;
  name?: string;
}

function ordinal(value: number): string {
  const lastTwoDigits = value % 100;
  if (lastTwoDigits >= 11 && lastTwoDigits <= 13) return `${value}th`;

  switch (value % 10) {
    case 1:
      return `${value}st`;
    case 2:
      return `${value}nd`;
    case 3:
      return `${value}rd`;
    default:
      return `${value}th`;
  }
}

function attendanceDescription(attendance: Attendance): string | undefined {
  const party = attendance.partyKind === "claimant"
    ? "the claimant"
    : `the ${ordinal(attendance.partyNumber)} defendant`;

  switch (attendance.choice) {
    case "counsel":
      return `counsel for ${party}`;
    case "solicitor":
      return `solicitor for ${party}`;
    case "solicitors-agent":
      return `solicitor's agent for ${party}`;
    case "housing-officer":
      return `the housing officer on behalf of ${party}`;
    case "duty-adviser":
      return `the duty adviser on behalf of ${party}`;
    case "in-person":
      return attendance.partyKind === "claimant"
        ? "claimant acting in person"
        : `${party} acting in person`;
    case "letter":
      return `written representations on behalf of ${party}`;
    case "not-present":
      return undefined;
  }
}

function buildAttendanceClause(
  attendances: readonly Attendance[],
): string | undefined {
  const descriptions = attendances.flatMap((attendance) => {
    const description = attendanceDescription(attendance);
    if (!description) return [];

    const name = attendance.name?.trim();
    return name ? [`${name}, ${description}`] : [description];
  });

  if (descriptions.length === 0) return undefined;

  const attendees = descriptions.length === 1
    ? descriptions[0]
    : `${descriptions.slice(0, -1).join(", ")} and ${descriptions.at(-1)}`;
  return `The Court heard from ${attendees}.`;
}

const orderTextCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="checked"]',
);
const secondSubparagraphCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="subpara-2"]',
);
const thirdSubparagraphCheckbox = document.querySelector<HTMLInputElement>(
  '[name="structure-choice"][value="subpara-3"]',
);
const attendanceRows = document.querySelectorAll<HTMLElement>(
  "[data-attendance-row]",
);

if (
  !orderTextCheckbox ||
  !secondSubparagraphCheckbox ||
  !thirdSubparagraphCheckbox ||
  attendanceRows.length === 0
) {
  throw new Error("The order controls are missing");
}

const orderTextControl = orderTextCheckbox;
const secondSubparagraphControl = secondSubparagraphCheckbox;
const thirdSubparagraphControl = thirdSubparagraphCheckbox;

const controller = createOrderEditor("#editor");

function readAttendances(): Attendance[] {
  return [...attendanceRows].map((row) => {
    const selectedChoice = row.querySelector<HTMLInputElement>(
      'input[type="radio"]:checked',
    );
    const name = row.querySelector<HTMLInputElement>('input[type="text"]');
    const partyKind = row.dataset.partyKind as PartyKind | undefined;
    const partyNumber = Number(row.dataset.partyNumber);

    if (!selectedChoice || !name || !partyKind || !partyNumber) {
      throw new Error("An attendance row is incomplete");
    }

    return {
      partyKind,
      partyNumber,
      choice: selectedChoice.value as AttendanceChoice,
      name: name.value,
    };
  });
}

function buildOrder(): ProseMirrorNode {
  const builder = createOrderBuilder();

  const attendanceClause = buildAttendanceClause(readAttendances());
  if (attendanceClause) {
    builder.setClause("attendance", attendanceClause);
  }

  if (orderTextControl.checked) {
    builder.setClause("order-text", "IT IS ORDERED THAT:");
  }

  const list = builder.buildList("order-clauses")
    .listItem("li-1", "foo");

  if (secondSubparagraphControl.checked) {
    list.listItem("li-2", "bar");
  }
  if (thirdSubparagraphControl.checked) {
    list.listItem("li-3", "baz");
  }

  list.build();
  builder.setClause("outro", "that's all folks");
  return builder.build();
}

function updateStructure(): void {
  controller.render(buildOrder());
}

orderTextControl.addEventListener("change", updateStructure);
secondSubparagraphControl.addEventListener("change", updateStructure);
thirdSubparagraphControl.addEventListener("change", updateStructure);
for (const row of attendanceRows) {
  row.addEventListener("change", updateStructure);
  row.addEventListener("input", updateStructure);
}
updateStructure();
