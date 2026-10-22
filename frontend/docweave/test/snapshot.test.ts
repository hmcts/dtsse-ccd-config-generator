import assert from "node:assert/strict";
import { after, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import {
  buildDoc,
  createDocEditor,
  describeChanges,
  InvalidSnapshotError,
  parseSnapshot,
  renderHtml,
  renderText,
} from "../src/index.js";
import { clauses, edited, generatedOrder, userClause } from "./fixtures/order.js";

/** As posted: ProseMirror gives attributes no prototype, which strict comparison notices. */
const json = (value: unknown): unknown => JSON.parse(JSON.stringify(value));

function rejects(value: unknown, code: InvalidSnapshotError["code"]): void {
  assert.throws(
    () => parseSnapshot(value),
    (error) => error instanceof InvalidSnapshotError && error.code === code,
  );
}

const unsupported = () => ({ ...generatedOrder(), version: 2 as 1 });

describe("parseSnapshot", () => {
  it("takes a snapshot as posted JSON or as a value, returning a copy", () => {
    const snapshot = generatedOrder();

    assert.deepEqual(parseSnapshot(JSON.stringify(snapshot)), snapshot);
    assert.notEqual(parseSnapshot(snapshot), snapshot);
    assert.deepEqual(parseSnapshot(snapshot), snapshot);
  });

  it("keeps the reader's own wording and clauses", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      clauses(current).push(userClause("The defendant may apply to vary this order."));
    });

    assert.deepEqual(describeChanges(parseSnapshot(snapshot)), { inserted: 1, modified: 0 });
  });

  it("refuses what is not a snapshot", () => {
    rejects("{not json", "malformed");
    rejects("null", "malformed");
    rejects(null, "malformed");
    rejects({ schema: "docweave-document", version: 1, current: {} }, "malformed");
    rejects({ ...generatedOrder(), version: 2 }, "unsupported-version");
    rejects({ ...generatedOrder(), current: { type: "nonsense" } }, "invalid-document");
  });

  it("refuses a generated clause removed outside the editor", () => {
    rejects(edited(generatedOrder(), (current) => clauses(current).pop()), "invalid-document");
  });

  it("refuses attributes of the wrong type", () => {
    rejects(edited(generatedOrder(), (current) => {
      current.content!.push({ type: "heading", attrs: { level: 9 }, content: [{ type: "text", text: "Order" }] });
    }), "invalid-document");
    rejects(edited(generatedOrder(), (current) => {
      current.content![0]!.attrs = { id: 7 };
    }), "invalid-document");
  });

  it("is what renderHtml and describeChanges check, so an invalid snapshot is never rendered", () => {
    assert.throws(() => renderHtml(unsupported()), InvalidSnapshotError);
    assert.throws(() => renderText(unsupported()), InvalidSnapshotError);
    assert.throws(() => describeChanges(unsupported()), InvalidSnapshotError);
    assert.throws(() => createDocEditor({ initialSnapshot: unsupported() }), InvalidSnapshotError);
  });
});

describe("renderText", () => {
  it("gives one line per paragraph or heading, facts as their values", () => {
    const snapshot = edited(generatedOrder(), (current) => {
      current.content!.unshift({ type: "heading", attrs: { level: 2 }, content: [{ type: "text", text: "Order" }] });
    });

    assert.equal(
      renderText(snapshot),
      "Order\nIT IS ORDERED THAT:\nThe defendant must give up possession by 1 October 2026.\n" +
        "The defendant must pay the claimant's costs.",
    );
  });

  it("includes nested clauses and every paragraph of a clause", () => {
    const controller = createDocEditor();
    controller.render(buildDoc((doc) => {
      doc.orderedList("clauses", (list) => {
        list.item("parent", "Parent clause.", (item) => {
          item.orderedList("children", (children) => children.item("child", "Child clause."));
        });
      });
    }));

    assert.equal(renderText(controller.getSnapshot()), "Parent clause.\nChild clause.");
  });
});

describe("createDocEditor's field", () => {
  const dom = new JSDOM("<!doctype html><textarea></textarea>");
  after(() => dom.window.close());
  const field = (value = ""): HTMLTextAreaElement => {
    const textarea = dom.window.document.createElement("textarea");
    textarea.value = value;
    return textarea;
  };
  const order = (date: string) => buildDoc((doc) => {
    doc.paragraph("deadline", (content) => content.text("Pay by ").fact("date", date));
  });

  it("holds every change as JSON, written before onChange is called", () => {
    const textarea = field();
    const seen: string[] = [];
    const controller = createDocEditor({ field: textarea, onChange: () => seen.push(textarea.value) });

    controller.render(order("1 May"));

    assert.deepEqual(JSON.parse(textarea.value), json(controller.getSnapshot()));
    assert.deepEqual(seen, [textarea.value]);
  });

  it("starts from the snapshot it holds, keeping the reader's edits", () => {
    const saved = edited(generatedOrder(), (current) => {
      clauses(current).push(userClause("Liberty to apply."));
    });

    const controller = createDocEditor({ field: field(JSON.stringify(saved)) });

    assert.deepEqual(json(controller.getSnapshot()), json(saved));
  });

  it("reports what it cannot read and leaves it unwritten until the host empties it", () => {
    const textarea = field("{not json");
    const errors: InvalidSnapshotError[] = [];
    const controller = createDocEditor({ field: textarea, onError: (error) => errors.push(error) });

    controller.render(order("1 May"));
    assert.deepEqual(errors.map((error) => error.code), ["malformed"]);
    assert.equal(textarea.value, "{not json");

    textarea.value = "";
    controller.render(order("2 May"));
    assert.deepEqual(JSON.parse(textarea.value), json(controller.getSnapshot()));
  });

  it("keeps the saved document when given an initialSnapshot it refuses", () => {
    const saved = JSON.stringify(generatedOrder());
    const textarea = field(saved);

    assert.throws(() => createDocEditor({ field: textarea, initialSnapshot: unsupported() }), InvalidSnapshotError);
    assert.equal(textarea.value, saved);
  });

  function posted(form: HTMLFormElement): FormData {
    const formData = new dom.window.FormData(form);
    // jsdom does not emit the browser's formdata event when building the entry list.
    form.dispatchEvent(Object.assign(new dom.window.Event("formdata"), { formData }));
    return formData;
  }

  it("posts UTF-8 base64url repeatedly while leaving the current snapshot as JSON", () => {
    const form = dom.window.document.createElement("form");
    const textarea = field();
    textarea.name = "document";
    form.append(textarea);
    const other = dom.window.document.createElement("input");
    other.name = "action";
    other.value = "draft";
    form.append(other);
    const controller = createDocEditor({ field: textarea });

    for (const wording of ["Pay £100 — café 🏠".repeat(10_000), "Corrected wording"]) {
      controller.render(order(wording));
      const before = textarea.value;
      for (let attempt = 0; attempt < 2; attempt++) {
        const body = posted(form);
        assert.equal(body.get("document"), Buffer.from(before, "utf8").toString("base64url"));
        assert.deepEqual(parseSnapshot(Buffer.from(String(body.get("document")), "base64url").toString("utf8")),
          parseSnapshot(before));
        assert.equal(body.get("action"), "draft");
        assert.equal(textarea.value, before);
      }
    }
    controller.destroy();
    assert.equal(posted(form).get("document"), textarea.value);
  });

  it("leaves empty fields empty and does not submit disabled or unnamed fields", () => {
    const form = dom.window.document.createElement("form");
    const textarea = field();
    textarea.name = "document";
    form.append(textarea);
    const controller = createDocEditor({ field: textarea });
    assert.equal(posted(form).get("document"), "");

    controller.render(order("1 May"));
    textarea.disabled = true;
    assert.equal(posted(form).has("document"), false);
    textarea.disabled = false;
    textarea.name = "";
    assert.equal([...posted(form)].length, 0);
    controller.destroy();
  });

  it("binds fields associated through the form attribute", () => {
    const form = dom.window.document.createElement("form");
    form.id = "order";
    const textarea = field();
    textarea.name = "document";
    textarea.setAttribute("form", form.id);
    dom.window.document.body.append(form, textarea);
    const controller = createDocEditor({ field: textarea });
    controller.render(order("1 May"));

    assert.equal(posted(form).get("document"), Buffer.from(textarea.value).toString("base64url"));
    controller.destroy();
    const replacement = createDocEditor({ field: textarea });
    assert.equal(posted(form).get("document"), Buffer.from(textarea.value).toString("base64url"));
    replacement.destroy();
    form.remove();
    textarea.remove();
  });
});
