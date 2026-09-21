/**
 * Dates a template works out when it is inserted. An author writes a date as a
 * variable, "[date: Hearing date]", and refers to it again with an offset,
 * "[date+14d]". A template holds each as a `template_date` node. Inserting the
 * template asks for each variable once and turns every date into ordinary
 * text, so nothing here ever reaches a document.
 */

export const TEMPLATE_DATE_NODE = "template_date";
export const TEMPLATE_DATE_TODAY = "today";
export const TEMPLATE_DATE_MAX_OFFSET = 9999;
export const TEMPLATE_DATE_MAX_LABEL_LENGTH = 100;

const units = ["days", "weeks", "months"] as const;
export type TemplateDateUnit = typeof units[number];

export interface TemplateDate {
  /** The variable: "date", "date2", ... or "today", which needs no asking. */
  name: string;
  /** What the reader is asked for. An author gives it on any one use of the variable. */
  label?: string | null;
  /** Whole units after the variable's date; negative for before it. */
  offset: number;
  unit: TemplateDateUnit;
}

type JsonNode = Record<string, unknown>;

const NAME = "date\\d*|today";
const MINUS = "\\-–−";
/**
 * A bracket that starts like a date. "[date not provided]" does not: a name is
 * followed at once by an offset, a label or the closing bracket.
 */
const writtenDates = new RegExp(`\\[((?:${NAME})\\s*(?:[+${MINUS}:][^[\\]]*)?)\\]`, "giu");
/**
 * The inside of one. Word turns a typed hyphen into a dash, so every kind of
 * minus is accepted, and "14 days" as well as "14d".
 */
const writtenDate = new RegExp(
  `^(${NAME})\\s*(?:([+${MINUS}])\\s*(\\d{1,4})\\s*(days?|weeks?|months?|[dwm]))?\\s*(?::(.*))?$`,
  "iu",
);
/** What still looks like a date once the ones that could be read are gone. */
const unreadDate = new RegExp(
  `\\[(?:${NAME})\\s*(?:\\]|[+${MINUS}:][^\\]\\ufffc]*\\]?)`,
  "iu",
);

function readWrittenDate(inner: string): TemplateDate | undefined {
  const match = writtenDate.exec(inner);
  if (!match) return undefined;
  const [, name, sign, amount, unit, label] = match;
  try {
    return readTemplateDate({
      name,
      label: label === undefined ? null : label,
      offset: amount === undefined ? 0 : Number(amount) * (sign === "+" ? 1 : -1),
      unit: units.find((candidate) => candidate[0] === unit?.[0]?.toLowerCase()) ?? "days",
    });
  } catch {
    return undefined;
  }
}

/** Checks a date's attributes and tidies them. Throws if they are not valid. */
export function readTemplateDate(attrs: unknown): TemplateDate {
  const { name, label = null, offset = 0, unit = "days" } =
    (attrs ?? {}) as Partial<TemplateDate>;
  if (typeof name !== "string" || !new RegExp(`^(?:${NAME})$`, "iu").test(name)) {
    throw new Error("Template date name is not valid");
  }
  const tidied = typeof label === "string" ? label.replace(/\s+/gu, " ").trim() : label;
  if (tidied !== null && (typeof tidied !== "string" || tidied === "" ||
    tidied.length > TEMPLATE_DATE_MAX_LABEL_LENGTH || /[[\]]/u.test(tidied) ||
    name.toLowerCase() === TEMPLATE_DATE_TODAY)) {
    throw new Error("Template date label is not valid");
  }
  if (!Number.isInteger(offset) || Math.abs(offset) > TEMPLATE_DATE_MAX_OFFSET) {
    throw new Error("Template date offset is not valid");
  }
  if (!units.includes(unit)) throw new Error("Template date unit is not valid");
  return { name: name.toLowerCase(), label: tidied, offset, unit };
}

/** "[date+14d]" or "[date: Hearing date]": a date as its author writes it. */
export function writeTemplateDate({ name, label, offset, unit }: TemplateDate): string {
  const written = offset === 0 ? "" : `${offset > 0 ? "+" : "-"}${Math.abs(offset)}${unit[0]}`;
  return `[${name}${written}${label ? `: ${label}` : ""}]`;
}

/** The calendar day at midnight UTC, so no clock change can move a date. */
export function calendarDay(year: number, month: number, day: number): Date {
  return new Date(Date.UTC(year, month - 1, day));
}

/** Reads a day, month and year as typed. Undefined unless it is a real date. */
export function parseTypedDate(
  day: string,
  month: string,
  year: string,
): Date | undefined {
  if (![day, month, year].every((part) => /^\s*\d+\s*$/u.test(part))) return undefined;
  const [d, m, y] = [Number(day), Number(month), Number(year)];
  if (y < 1000 || y > 9999) return undefined;
  const date = calendarDay(y, m, d);
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 &&
      date.getUTCDate() === d
    ? date
    : undefined;
}

export function addToDate(date: Date, offset: number, unit: TemplateDateUnit): Date {
  const result = new Date(date);
  if (unit !== "months") {
    result.setUTCDate(result.getUTCDate() + offset * (unit === "weeks" ? 7 : 1));
    return result;
  }
  // A month on from the 31st is the last day of a shorter month, not a day
  // into the month after.
  result.setUTCDate(1);
  result.setUTCMonth(result.getUTCMonth() + offset);
  const lastDay = new Date(Date.UTC(
    result.getUTCFullYear(),
    result.getUTCMonth() + 1,
    0,
  )).getUTCDate();
  result.setUTCDate(Math.min(date.getUTCDate(), lastDay));
  return result;
}

const written = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "long",
  year: "numeric",
  timeZone: "UTC",
});
/** "2 October 2026". */
export function formatDate(date: Date): string {
  return written.format(date);
}

function visit(node: unknown, found: TemplateDate[]): void {
  if (!node || typeof node !== "object") return;
  const { type, attrs, content } = node as JsonNode;
  if (type === TEMPLATE_DATE_NODE) found.push(readTemplateDate(attrs));
  if (Array.isArray(content)) content.forEach((child) => visit(child, found));
}

/** Every date in a template's content, in reading order. */
export function templateDates(content: unknown): TemplateDate[] {
  const found: TemplateDate[] = [];
  visit(content, found);
  return found;
}

/** The label each variable was given, by the first use that gives one. */
export function templateDateLabels(content: unknown): Map<string, string> {
  const labels = new Map<string, string>();
  for (const { name, label } of templateDates(content)) {
    if (label && !labels.has(name)) labels.set(name, label);
  }
  return labels;
}

/** The variables the reader has to be asked for, each once, in reading order. */
export function templateDateVariables(content: unknown): string[] {
  return [...new Set(
    templateDates(content)
      .map((date) => date.name)
      .filter((name) => name !== TEMPLATE_DATE_TODAY),
  )];
}

/** Text nodes with the same marks, side by side, which ProseMirror never leaves. */
function joined(nodes: JsonNode[]): JsonNode[] {
  const result: JsonNode[] = [];
  for (const next of nodes) {
    const previous = result.at(-1);
    if (previous?.type === "text" && next.type === "text" &&
      JSON.stringify(previous.marks ?? []) === JSON.stringify(next.marks ?? [])) {
      result[result.length - 1] = {
        ...previous,
        text: `${String(previous.text)}${String(next.text)}`,
      };
    } else {
      result.push(next);
    }
  }
  return result;
}

/** Rebuilds content, letting `replace` swap any node for the nodes that stand in for it. */
function rewrite(node: unknown, replace: (node: JsonNode) => JsonNode[] | undefined): JsonNode[] {
  if (!node || typeof node !== "object") return [node as JsonNode];
  const replaced = replace(node as JsonNode);
  if (replaced) return replaced;
  const { content } = node as JsonNode;
  if (!Array.isArray(content)) return [node as JsonNode];
  return [{
    ...(node as JsonNode),
    content: joined(content.flatMap((child) => rewrite(child, replace))),
  }];
}

function datesAsText<T>(content: T, write: (date: TemplateDate) => string): T {
  return rewrite(content, ({ type, attrs, marks }) =>
    type === TEMPLATE_DATE_NODE
      ? [{
        type: "text",
        text: write(readTemplateDate(attrs)),
        ...(marks === undefined ? {} : { marks }),
      }]
      : undefined)[0] as T;
}

/**
 * The template's content with every date written out as text: the wording that
 * goes into the document. `values` holds the reader's date for each variable.
 */
export function resolveTemplateDates<T>(
  content: T,
  values: ReadonlyMap<string, Date>,
  today: Date,
): T {
  return datesAsText(content, ({ name, offset, unit }) => {
    const base = name === TEMPLATE_DATE_TODAY ? today : values.get(name);
    if (!base) throw new Error(`No date was given for [${name}]`);
    return formatDate(addToDate(base, offset, unit));
  });
}

/** Content as its author edits it: each date as the text they would write for it. */
export function writeTemplateDates<T>(content: T): T {
  return datesAsText(content, writeTemplateDate);
}

/** Content as it is stored: each date an author wrote, as a date. */
export function readTemplateDates<T>(content: T): T {
  return rewrite(content, ({ type, text, marks }) => {
    if (type !== "text" || typeof text !== "string") return undefined;
    const nodes: JsonNode[] = [];
    let from = 0;
    const push = (value: string): void => {
      if (value) nodes.push({ type: "text", text: value, ...(marks ? { marks } : {}) });
    };
    for (const match of text.matchAll(writtenDates)) {
      const date = readWrittenDate(match[1]!);
      if (!date) continue;
      push(text.slice(from, match.index));
      nodes.push({ type: TEMPLATE_DATE_NODE, attrs: { ...date }, ...(marks ? { marks } : {}) });
      from = match.index + match[0].length;
    }
    push(text.slice(from));
    return nodes;
  })[0] as T;
}

/** A mistyped date, or one only partly in bold, left as text after reading the rest. */
function unreadTemplateDate(content: unknown): string | undefined {
  if (!content || typeof content !== "object") return undefined;
  const children = (content as JsonNode).content;
  if (!Array.isArray(children)) return undefined;
  // Read across a paragraph's runs of text, so a date that is partly in bold
  // is quoted whole.
  const wording = children
    .map((child: JsonNode) => child?.type === "text" ? String(child.text) : "\ufffc")
    .join("");
  const found = unreadDate.exec(wording)?.[0];
  if (found) return found;
  for (const child of children) {
    const unread = unreadTemplateDate(child);
    if (unread) return unread;
  }
  return undefined;
}

/**
 * Why stored content cannot be saved as it stands, in words for its author.
 * Undefined when every date can be worked out and asked for by name.
 */
export function templateDateProblem(content: unknown): string | undefined {
  const unread = unreadTemplateDate(content);
  if (unread) {
    return `Check ${unread}. Write a date like [date: Hearing date], then [date+14d] ` +
      "for 14 days later. Use d, w or m, + or -, with none of it in bold or italic on its own.";
  }
  const labels = new Map<string, string>();
  for (const { name, label } of templateDates(content)) {
    const known = labels.get(name);
    if (label && known && known !== label) {
      return `[${name}] is called both "${known}" and "${label}". Give it one name, ` +
        `or use [date${Number(name.slice(4) || 1) + 1}] for a different date.`;
    }
    if (label) labels.set(name, label);
  }
  const unnamed = templateDateVariables(content).find((name) => !labels.has(name));
  return unnamed &&
    `Say what [${unnamed}] is, like [${unnamed}: Hearing date], so you can be asked for it.`;
}
