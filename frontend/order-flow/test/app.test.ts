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
    assert.match(response.text, /name="test-checkbox"/);
    assert.match(response.text, /Check this box/);
    assert.match(response.text, /id="editor"/);
    assert.match(response.text, /Hello ProseMirror/);
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
