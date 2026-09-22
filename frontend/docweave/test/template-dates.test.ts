import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { editorSchema } from "../src/schema.js";
import {
  calendarDay,
  parseTypedDate,
  readTemplateDates,
  resolveTemplateDates,
  templateDateLabels,
  templateDateProblem,
  templateDateVariables,
  writeTemplateDates,
} from "../src/templates/dates.js";
import { parseTemplateFragment } from "../src/templates/provider.js";

const strong = [{ type: "strong" }];
const text = (value: string, marks?: unknown[]) =>
  ({ type: "text", text: value, ...(marks ? { marks } : {}) });
const date = (name: string, offset = 0, unit = "days", label: string | null = null) =>
  ({ type: "template_date", attrs: { name, label, offset, unit } });
const doc = (...content: unknown[]) =>
  ({ type: "doc", content: [{ type: "paragraph", content }] });
const fragment = (content: unknown) =>
  ({ schema: "docweave-template", version: 1, content });

/** The wording a template becomes once its dates are worked out. */
function wording(content: unknown, values: Record<string, Date> = {}): string {
  const resolved = resolveTemplateDates(
    content,
    new Map(Object.entries(values)),
    calendarDay(2026, 9, 20),
  );
  return parseTemplateFragment(fragment(resolved), { dates: false }).document.textContent;
}

describe("template dates", () => {
  it("writes each date out from its variable, and today's without asking", () => {
    const content = doc(
      text("Heard "), date("date", 0, "days", "Hearing date"),
      text(", file by "), date("date", 14),
      text(", serve by "), date("date", 6, "weeks"),
      text(", reply to "), date("date2", -1, "days", "Date of service"),
      text(", made "), date("today"),
    );

    assert.deepEqual(templateDateVariables(content), ["date", "date2"]);
    assert.deepEqual([...templateDateLabels(content)], [
      ["date", "Hearing date"], ["date2", "Date of service"],
    ]);
    assert.equal(
      wording(content, { date: calendarDay(2026, 10, 2), date2: calendarDay(2026, 10, 9) }),
      "Heard 2 October 2026, file by 16 October 2026, serve by 13 November 2026, " +
      "reply to 8 October 2026, made 20 September 2026",
    );
    // Wording is never written with a date missing.
    assert.throws(() => wording(content, { date: calendarDay(2026, 10, 2) }), /\[date2\]/u);
  });

  it("ends a month on from the 31st on the last day of a shorter month", () => {
    const months = (from: Date, offset: number) =>
      wording(doc(date("date", offset, "months", "From")), { date: from });

    assert.equal(months(calendarDay(2027, 1, 31), 1), "28 February 2027");
    assert.equal(months(calendarDay(2028, 1, 31), 1), "29 February 2028");
    assert.equal(months(calendarDay(2026, 12, 15), 2), "15 February 2027");
    assert.equal(months(calendarDay(2026, 3, 31), -1), "28 February 2026");
  });

  it("counts days across a clock change without losing one", () => {
    assert.equal(
      wording(doc(date("date", 1), text(" "), date("date", 7)), { date: calendarDay(2026, 10, 24) }),
      "25 October 2026 31 October 2026",
    );
  });

  it("reads only a real date", () => {
    assert.deepEqual(parseTypedDate(" 29", "02 ", "2028"), calendarDay(2028, 2, 29));
    const notDates: Array<[string, string, string]> = [
      ["29", "2", "2027"], ["31", "4", "2026"], ["0", "1", "2026"], ["1", "13", "2026"],
      ["1", "1", "26"], ["x", "1", "2026"], ["1", "", "2026"],
    ];
    for (const parts of notDates) {
      assert.equal(parseTypedDate(...parts), undefined, parts.join("/"));
    }
  });
});

describe("template dates in stored content", () => {
  it("rejects a date that could not be worked out", () => {
    for (const attrs of [
      { offset: 1, unit: "days" },
      { name: "hearing", offset: 1, unit: "days" },
      { name: "date", label: "  ", offset: 1, unit: "days" },
      { name: "date", label: "Hearing [date]", offset: 1, unit: "days" },
      { name: "today", label: "Today", offset: 1, unit: "days" },
      { name: "date", offset: 1.5, unit: "days" },
      { name: "date", offset: 10000, unit: "days" },
      { name: "date", offset: 1, unit: "years" },
    ]) {
      assert.throws(
        () => parseTemplateFragment(fragment(doc({ type: "template_date", attrs }))),
        /Template date/u,
        JSON.stringify(attrs),
      );
    }
  });

  it("refuses wording for a document whose dates have not been written out", () => {
    // A document's schema has no such node, so no date can ever be in one.
    assert.equal(editorSchema.nodes.template_date, undefined);
    assert.throws(
      () => parseTemplateFragment(fragment(doc(date("today"))), { dates: false }),
      /must be worked out before inserting/u,
    );
  });
});

describe("dates as an author writes them", () => {
  it("reads each written date, and writes it back for editing", () => {
    for (const [written, stored, rewritten] of [
      ["[date: Hearing date]", date("date", 0, "days", "Hearing date"), "[date: Hearing date]"],
      ["[date+14d]", date("date", 14), "[date+14d]"],
      ["[Date2 – 3 Months:  Date of  service ]", date("date2", -3, "months", "Date of service"),
        "[date2-3m: Date of service]"],
      ["[date + 1 day]", date("date", 1), "[date+1d]"],
      ["[TODAY+6w]", date("today", 6, "weeks"), "[today+6w]"],
      ["[today]", date("today"), "[today]"],
    ] as const) {
      const content = readTemplateDates(doc(text(`By ${written}.`, strong), text(" [date not provided]")));
      assert.deepEqual(
        content,
        doc(text("By ", strong), { ...stored, marks: strong }, text(".", strong), text(" [date not provided]")),
        written,
      );
      assert.deepEqual(
        writeTemplateDates(content),
        doc(text(`By ${rewritten}.`, strong), text(" [date not provided]")),
        written,
      );
    }
  });

  it("says why dates cannot be saved, rather than storing them as wording", () => {
    const labelled = "[date: Hearing date] ";
    for (const [content, problem] of [
      [[text(`${labelled}[date+14 fortnights]`)], /^Check \[date\+14 fortnights\]\. Write a date like/u],
      [[text(`${labelled}[date+14]`)], /^Check \[date\+14\]/u],
      [[text(`${labelled}[date+14d`)], /^Check \[date\+14d\./u],
      [[text(`${labelled}[date`), text("+14", strong), text("d]")], /^Check \[date\+14d\]/u],
      [[text("[today: Order date]")], /^Check \[today: Order date\]/u],
      [[text(`${labelled}[date2+14d]`)], /^Say what \[date2\] is, like \[date2: Hearing date\]/u],
      [[text(`${labelled}[date+7d: Date of hearing]`)],
        /^\[date\] is called both "Hearing date" and "Date of hearing"\. .* use \[date2\]/u],
    ] as const) {
      assert.match(templateDateProblem(readTemplateDates(doc(...content))) ?? "", problem);
    }
    for (const fine of [
      "[date not provided] [dates] [insert name] [today is the day]",
      "File by [date+28d: Hearing date], or [date+6w] at the latest, from [today].",
      "[date: Hearing date] and again [date: Hearing date]",
    ]) {
      assert.equal(templateDateProblem(readTemplateDates(doc(text(fine)))), undefined, fine);
    }
  });
});
