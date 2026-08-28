import assert from "node:assert/strict";
import { describe, it } from "node:test";

import request from "supertest";

import { createApp } from "../src/app.js";

describe("order flow page", () => {
  it("renders the GOV.UK page shell and ProseMirror mount points", async () => {
    const response = await request(createApp())
      .get("/")
      .expect(200)
      .expect("content-type", /html/);

    assert.match(response.text, /class="govuk-template"/);
    assert.match(response.text, /id="main-content"/);
    assert.match(response.text, /Check this box/);
    assert.match(response.text, /name="structure-choice"/);
    assert.match(response.text, /class="govuk-checkboxes structure-checkboxes"/);
    assert.match(response.text, /value="checked" checked/);
    assert.match(response.text, /value="sublist" checked/);
    assert.match(response.text, /\sSublist\s/);
    assert.match(response.text, /value="subpara-1" checked/);
    assert.match(response.text, /\sSubpara 1\s/);
    assert.match(response.text, /value="subpara-2"/);
    assert.match(response.text, /\sSubpara 2\s/);
    assert.match(response.text, /value="subpara-3"/);
    assert.match(response.text, /\sSubpara 3\s/);
    assert.match(response.text, /id="editor"/);
    assert.match(response.text, /\/assets\/application\.css/);
    assert.match(response.text, /\/assets\/application\.js/);
    assert.doesNotMatch(response.text, /<header\b/);
    assert.doesNotMatch(response.text, /<footer\b/);
    assert.equal(response.headers["x-powered-by"], undefined);
  });

  it("returns not found for unknown routes", async () => {
    await request(createApp()).get("/not-found").expect(404);
  });
});
