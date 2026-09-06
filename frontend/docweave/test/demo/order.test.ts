import assert from "node:assert/strict";
import { describe, it } from "node:test";

import type { DemoOrderInputs } from "../../examples/court-order/inputs.js";
import { buildDemoOrder } from "../../examples/court-order/order.js";
import { getDocumentFactSources } from "../../src/builder.js";

const defaultInputs: DemoOrderInputs = {
  attendances: [{
    partyKind: "claimant",
    partyNumber: 1,
    choice: "counsel",
    name: "Alex Smith",
  }],
  orderDate: "6 September 2026",
  fixedCostsAmount: "£355",
  arrearsAmount: "£2,342",
  initialPaymentAmount: "£213",
  initialPaymentDate: "14 September 2026",
  monthlyPaymentAmount: "£655",
  firstMonthlyPaymentDate: "28 September 2026",
  includeOrderHeading: true,
  useAlternativePossessionWording: false,
  includeRepairsClause: false,
  includeCostsClause: false,
  includeSuspendedPossession: true,
  includePaymentByDate: true,
  includeMonthlyPayments: true,
};

describe("court-order document", () => {
  it("builds coherent default wording without changing values", () => {
    const text = buildDemoOrder(defaultInputs).node.textContent;

    assert.match(text, /Alex Smith, counsel for the claimant/);
    assert.match(text, /6 September 2026/);
    assert.match(text, /payment of £213/);
    assert.match(text, /monthly payments of £655/);
    assert.doesNotMatch(text, /\d{18}/);
  });

  it("adds and removes optional clauses from the typed inputs", () => {
    const text = buildDemoOrder({
      ...defaultInputs,
      includeRepairsClause: true,
      includeCostsClause: true,
      includeMonthlyPayments: false,
    }).node.textContent;

    assert.match(text, /reported disrepair/);
    assert.match(text, /fixed costs of £355/);
    assert.doesNotMatch(text, /monthly payments of £655/);
  });

  it("builds every date and money value as a linked fact", () => {
    const document = buildDemoOrder({
      ...defaultInputs,
      includeCostsClause: true,
    });
    const facts: Array<{ id: string; text: string }> = [];

    document.node.descendants((node) => {
      if (node.type.name === "generated_text") {
        facts.push({
          id: node.attrs.id as string,
          text: node.attrs.text as string,
        });
      }
    });

    assert.deepEqual(
      facts.map((fact) => fact.text),
      [
        "6 September 2026",
        "£355",
        "£2,342",
        "£213",
        "14 September 2026",
        "£655",
        "28 September 2026",
      ],
    );
    assert.deepEqual(
      [...getDocumentFactSources(document).values()],
      [
        "order-date",
        "fixed-costs-amount",
        "arrears-amount",
        "initial-payment-amount",
        "initial-payment-date",
        "monthly-payment-amount",
        "first-monthly-payment-date",
      ],
    );
  });
});
