import {
  type Attendance,
  type AttendanceChoice,
  type PartyKind,
} from "./attendance.js";

export interface DemoOrderInputs {
  attendances: Attendance[];
  orderDate: string;
  fixedCostsAmount: string;
  arrearsAmount: string;
  initialPaymentAmount: string;
  initialPaymentDate: string;
  monthlyPaymentAmount: string;
  firstMonthlyPaymentDate: string;
  includeOrderHeading: boolean;
  useAlternativePossessionWording: boolean;
  includeRepairsClause: boolean;
  includeCostsClause: boolean;
  includeSuspendedPossession: boolean;
  includePaymentByDate: boolean;
  includeMonthlyPayments: boolean;
}

function requiredElement<T extends Element>(
  root: ParentNode,
  selector: string,
): T {
  const element = root.querySelector<T>(selector);
  if (!element) throw new Error(`Demo control is missing: ${selector}`);
  return element;
}

function checked(form: HTMLFormElement, name: string, value: string): boolean {
  return requiredElement<HTMLInputElement>(
    form,
    `[name="${name}"][value="${value}"]`,
  ).checked;
}

export function formatOrderDate(
  dayValue: string,
  monthValue: string,
  yearValue: string,
): string {
  const day = Number(dayValue);
  const month = Number(monthValue);
  const year = Number(yearValue);
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

export function formatSterling(value: string): string {
  const normalizedValue = value.replaceAll(",", "").trim();
  if (normalizedValue.length === 0) {
    return "£[amount not provided]";
  }

  const amount = Number(normalizedValue);
  if (!Number.isFinite(amount) || amount < 0) {
    return "£[amount not provided]";
  }

  return `£${new Intl.NumberFormat("en-GB", {
    maximumFractionDigits: 2,
    minimumFractionDigits: Number.isInteger(amount) ? 0 : 2,
  }).format(amount)}`;
}

function readDate(form: HTMLFormElement, prefix: string): string {
  const day = requiredElement<HTMLInputElement>(
    form,
    `[name="${prefix}-day"]`,
  );
  const month = requiredElement<HTMLInputElement>(
    form,
    `[name="${prefix}-month"]`,
  );
  const year = requiredElement<HTMLInputElement>(
    form,
    `[name="${prefix}-year"]`,
  );

  return formatOrderDate(day.value, month.value, year.value);
}

function readMoney(form: HTMLFormElement, name: string): string {
  return formatSterling(
    requiredElement<HTMLInputElement>(form, `[name="${name}"]`).value,
  );
}

function readAttendances(form: HTMLFormElement): Attendance[] {
  return [...form.querySelectorAll<HTMLElement>("[data-attendance-row]")]
    .map((row) => {
      const selectedChoice = requiredElement<HTMLInputElement>(
        row,
        'input[type="radio"]:checked',
      );
      const name = requiredElement<HTMLInputElement>(
        row,
        'input[type="text"]',
      );
      const partyKind = row.dataset.partyKind as PartyKind | undefined;
      const partyNumber = Number(row.dataset.partyNumber);

      if (
        (partyKind !== "claimant" && partyKind !== "defendant") ||
        !Number.isInteger(partyNumber) ||
        partyNumber < 1
      ) {
        throw new Error("An attendance row has invalid party details");
      }

      return {
        partyKind,
        partyNumber,
        choice: selectedChoice.value as AttendanceChoice,
        name: name.value,
      };
    });
}

export function readInputs(form: HTMLFormElement): DemoOrderInputs {
  return {
    attendances: readAttendances(form),
    orderDate: readDate(form, "order-date"),
    fixedCostsAmount: readMoney(form, "fixed-costs-amount"),
    arrearsAmount: readMoney(form, "arrears-amount"),
    initialPaymentAmount: readMoney(form, "initial-payment-amount"),
    initialPaymentDate: readDate(form, "initial-payment-date"),
    monthlyPaymentAmount: readMoney(form, "monthly-payment-amount"),
    firstMonthlyPaymentDate: readDate(
      form,
      "first-monthly-payment-date",
    ),
    includeOrderHeading: checked(form, "clause-choice", "order-heading"),
    useAlternativePossessionWording: checked(
      form,
      "clause-choice",
      "alternative-possession-wording",
    ),
    includeRepairsClause: checked(form, "clause-choice", "repairs"),
    includeCostsClause: checked(form, "clause-choice", "costs"),
    includeSuspendedPossession: checked(
      form,
      "payment-choice",
      "suspended-possession",
    ),
    includePaymentByDate: checked(
      form,
      "payment-choice",
      "payment-by-date",
    ),
    includeMonthlyPayments: checked(
      form,
      "payment-choice",
      "monthly-payments",
    ),
  };
}
