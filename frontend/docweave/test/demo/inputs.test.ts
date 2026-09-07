import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  formatOrderDate,
  formatSterling,
} from "../../examples/court-order/inputs.js";

describe("court-order inputs", () => {
  it("formats a valid calendar date", () => {
    assert.equal(formatOrderDate("6", "9", "2026"), "6 September 2026");
  });

  it("uses a visible placeholder for an invalid calendar date", () => {
    assert.equal(formatOrderDate("31", "2", "2026"), "[date not provided]");
    assert.equal(formatOrderDate("", "9", "2026"), "[date not provided]");
  });

  it("formats money with the sterling symbol inside the value", () => {
    assert.equal(formatSterling("2342"), "£2,342");
    assert.equal(formatSterling("213.50"), "£213.50");
    assert.equal(formatSterling(""), "£[amount not provided]");
    assert.equal(formatSterling("not supplied"), "£[amount not provided]");
  });
});
