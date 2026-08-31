import { initAll } from "govuk-frontend";
import { type Node as ProseMirrorNode } from "prosemirror-model";

import { buildOrder } from "./builder.js";
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

function buildAttendanceRegister(
  attendances: readonly Attendance[],
): string | undefined {
  const descriptions = attendances.flatMap((attendance) => {
    const description = attendanceDescription(attendance);
    if (!description) return [];

    const name = attendance.name?.trim();
    return name ? [`${name}, ${description}`] : [description];
  });

  if (descriptions.length === 0) return undefined;

  return descriptions.length === 1
    ? descriptions[0]
    : `${descriptions.slice(0, -1).join(", ")} and ${descriptions.at(-1)}`;
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
const suspendedOrderCheckbox = document.querySelector<HTMLInputElement>(
  '[name="suspended-order-choice"][value="clause"]',
);
const paymentByDateCheckbox = document.querySelector<HTMLInputElement>(
  '[name="suspended-order-choice"][value="payment-by-date"]',
);
const monthlyPaymentsCheckbox = document.querySelector<HTMLInputElement>(
  '[name="suspended-order-choice"][value="monthly-payments"]',
);
const orderDateDay = document.querySelector<HTMLInputElement>(
  '[name="order-date-day"]',
);
const orderDateMonth = document.querySelector<HTMLInputElement>(
  '[name="order-date-month"]',
);
const orderDateYear = document.querySelector<HTMLInputElement>(
  '[name="order-date-year"]',
);
const attendanceRows = document.querySelectorAll<HTMLElement>(
  "[data-attendance-row]",
);

if (
  !orderTextCheckbox ||
  !secondSubparagraphCheckbox ||
  !thirdSubparagraphCheckbox ||
  !suspendedOrderCheckbox ||
  !paymentByDateCheckbox ||
  !monthlyPaymentsCheckbox ||
  !orderDateDay ||
  !orderDateMonth ||
  !orderDateYear ||
  attendanceRows.length === 0
) {
  throw new Error("The order controls are missing");
}

const orderTextControl = orderTextCheckbox;
const secondSubparagraphControl = secondSubparagraphCheckbox;
const thirdSubparagraphControl = thirdSubparagraphCheckbox;
const suspendedOrderControl = suspendedOrderCheckbox;
const paymentByDateControl = paymentByDateCheckbox;
const monthlyPaymentsControl = monthlyPaymentsCheckbox;
const orderDateControls = {
  day: orderDateDay,
  month: orderDateMonth,
  year: orderDateYear,
};

const documentDebug = document.querySelector<HTMLElement>("#document-debug");
const htmlDebug = document.querySelector<HTMLElement>("#html-debug");

const controller = createOrderEditor({
  mount: "#editor",
  toolbar: "#order-editor-toolbar",
  onChange(editorDocument) {
    if (documentDebug) documentDebug.textContent = JSON.stringify(editorDocument.current, null, 2);
    if (htmlDebug) htmlDebug.textContent = document.querySelector("#editor")?.innerHTML ?? "";
  },
});

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

function readOrderDate(): string {
  const day = Number(orderDateControls.day.value);
  const month = Number(orderDateControls.month.value);
  const year = Number(orderDateControls.year.value);
  const date = new Date(Date.UTC(year, month - 1, day));

  if (
    !Number.isInteger(day) ||
    !Number.isInteger(month) ||
    !Number.isInteger(year) ||
    date.getUTCDate() !== day ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCFullYear() !== year
  ) {
    return "[date not provided]";
  }

  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  }).format(date);
}

function currentTimeTicks(): string {
  const ticksAtUnixEpoch = 621355968000000000n;
  return (BigInt(Date.now()) * 10_000n + ticksAtUnixEpoch).toString();
}

function buildCurrentOrder(): ProseMirrorNode {
  return buildOrder((order) => {
    const attendance = buildAttendanceRegister(readAttendances());
    if (attendance) {
      order.paragraph("attendance", (content) => {
        content
          .text("The Court heard from ")
          .generatedText("register", attendance)
          .text(".");
      });
    }

    if (orderTextControl.checked) {
      order.paragraph("order-text", "IT IS ORDERED THAT:");
    }

    order.orderedList("order-clauses", (list) => {
      list.item("give-possession", (content) => {
        content
          .text("The defendants must give up possession on or before ")
          .generatedText("deadline", readOrderDate())
          .text(".");
      });

      if (secondSubparagraphControl.checked) {
        list.item("li-2", "bar");
      }
      if (thirdSubparagraphControl.checked) {
        list.item("li-3", "baz");
      }

      if (suspendedOrderControl.checked) {
        list.item(
          "suspended-possession",
          "Execution of the order for possession and enforcement of the money judgment are suspended as long as the defendant pays (i) the rent as it falls due plus (ii) the arrears of £2342.00 by:",
          (item) => {
            if (
              !paymentByDateControl.checked &&
              !monthlyPaymentsControl.checked
            ) {
              return;
            }

            item.orderedList("suspended-possession-subclauses", (subclauses) => {
              if (paymentByDateControl.checked) {
                subclauses.item(
                  "payment-by-date",
                  `payment of £213.00 to the claimant by 14 September 2026; ${currentTimeTicks()}`,
                );
              }
              if (monthlyPaymentsControl.checked) {
                subclauses.item(
                  "monthly-payments",
                  "payments of £655.00 to the claimant every month, the first instalment to be paid on or before 28 September 2026;",
                );
              }
            });
          },
        );
      }
    });

    order.paragraph("outro", "that's all folks");
  });
}

function updateStructure(): void {
  controller.render(buildCurrentOrder());
}

orderTextControl.addEventListener("change", updateStructure);
secondSubparagraphControl.addEventListener("change", updateStructure);
thirdSubparagraphControl.addEventListener("change", updateStructure);
suspendedOrderControl.addEventListener("change", updateStructure);
paymentByDateControl.addEventListener("change", updateStructure);
monthlyPaymentsControl.addEventListener("change", updateStructure);
for (const input of Object.values(orderDateControls)) {
  input.addEventListener("input", updateStructure);
}
for (const row of attendanceRows) {
  row.addEventListener("change", updateStructure);
  row.addEventListener("input", updateStructure);
}
updateStructure();
