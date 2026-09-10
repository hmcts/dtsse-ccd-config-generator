import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { JSDOM } from "jsdom";
import request from "supertest";

import {
  formatOrderDate,
  readInputs,
} from "../../examples/court-order/inputs.js";
import { createApp } from "../../examples/court-order/server/app.js";

describe("court-order demo page", () => {
  it("renders the playground controls, editor and collapsed inspector", async () => {
    const response = await request(createApp())
      .get("/playground/")
      .expect(200)
      .expect("content-type", /html/);

    assert.match(response.text, /class="govuk-template"/);
    assert.match(response.text, />Court order playground</);
    assert.match(response.text, /Try the reconciliation flow/);
    assert.match(response.text, /Claimant: Mersey Community Housing/);
    assert.match(response.text, /name="attendance-claimant-1"/);
    assert.match(response.text, /value="housing-officer"/);
    assert.match(response.text, /name="attendance-defendant-2"/);
    assert.match(response.text, /value="duty-adviser"/);
    assert.match(response.text, /class="attendance-grid"/);
    assert.match(response.text, /H\/O or duty/);
    assert.match(response.text, /id="order-date"/);
    assert.match(response.text, /id="fixed-costs-amount"/);
    assert.match(response.text, /id="arrears-amount"/);
    assert.match(response.text, /id="initial-payment-date"/);
    assert.match(response.text, /id="first-monthly-payment-date"/);
    assert.match(response.text, /Use alternative possession wording/);
    assert.match(response.text, /Include disrepair inspection clause/);
    assert.match(response.text, /Include monthly payments/);
    assert.match(response.text, /Reset example/);
    assert.match(response.text, /data-order-controls/);
    assert.match(response.text, /id="editor"/);
    assert.match(response.text, /<details class="govuk-details document-inspector">/);
    assert.match(response.text, /id="current-document-json"/);
    assert.match(response.text, /id="generated-document-json"/);
    assert.doesNotMatch(response.text, /Schema nodes/);
    assert.doesNotMatch(response.text, /Editor HTML/);
    assert.match(response.text, /\/assets\/application\.css/);
    assert.match(response.text, /\/assets\/application\.js/);
    assert.equal(response.headers["x-powered-by"], undefined);
  });

  it("returns not found for unknown routes", async () => {
    await request(createApp()).get("/not-found").expect(404);
  });

  it("provides every control required to read the default order inputs", async () => {
    const response = await request(createApp()).get("/playground/").expect(200);
    const dom = new JSDOM(response.text);
    const form = dom.window.document.querySelector<HTMLFormElement>(
      "#order-form",
    );

    assert.ok(form);
    const inputs = readInputs(form);
    const today = new Date();

    assert.deepEqual(inputs.attendances, [
      {
        partyKind: "claimant",
        partyNumber: 1,
        choice: "counsel",
        name: "",
      },
      {
        partyKind: "defendant",
        partyNumber: 1,
        choice: "solicitor",
        name: "",
      },
      {
        partyKind: "defendant",
        partyNumber: 2,
        choice: "solicitors-agent",
        name: "",
      },
    ]);
    assert.equal(
      inputs.orderDate,
      formatOrderDate(
        String(today.getDate()),
        String(today.getMonth() + 1),
        String(today.getFullYear()),
      ),
    );
    assert.equal(inputs.fixedCostsAmount, "£355");
    assert.equal(inputs.arrearsAmount, "£2,342");
    assert.equal(inputs.initialPaymentAmount, "£213");
    assert.equal(inputs.initialPaymentDate, "14 September 2026");
    assert.equal(inputs.monthlyPaymentAmount, "£655");
    assert.equal(inputs.firstMonthlyPaymentDate, "28 September 2026");
    assert.equal(inputs.includeOrderHeading, true);
    assert.equal(inputs.useAlternativePossessionWording, false);
    assert.equal(inputs.includeRepairsClause, false);
    assert.equal(inputs.includeCostsClause, false);
    assert.equal(inputs.includeSuspendedPossession, true);
    assert.equal(inputs.includePaymentByDate, true);
    assert.equal(inputs.includeMonthlyPayments, true);

    dom.window.close();
  });
});
