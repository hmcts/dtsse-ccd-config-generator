import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";

import { JSDOM } from "jsdom";

import { BLOCKED_EDIT_MESSAGE } from "../src/diff-styling.js";
import { installJsdomGlobals } from "./jsdom-globals.js";

type Docweave = typeof import("../src/index.js");

let dom: JSDOM;
let restoreGlobals: () => void;

beforeEach(() => {
  dom = new JSDOM(
    `<!doctype html>
      <div class="form-group">
        <label for="deadline">Possession deadline</label>
        <input id="deadline" value="1 October 2026">
      </div>
      <label for="other">Another answer</label>
      <input id="other">
      <p id="static">Not a control</p>
      <div id="editor"></div>`,
    { pretendToBeVisual: true },
  );
  restoreGlobals = installJsdomGlobals(dom);
});

afterEach(() => restoreGlobals());

function buildTarget({ buildDoc }: Docweave) {
  return buildDoc((doc) => {
    doc.paragraph("heading", "IT IS ORDERED THAT:");
    doc.paragraph("possession", (content) => {
      content
        .text("The defendant must give up possession by ")
        .fact("deadline", "1 October 2026", { sourceId: "deadline" })
        .text(".");
    });
  });
}

function keydown(
  target: Element,
  key: string,
  init: KeyboardEventInit = {},
): KeyboardEvent {
  const event = new dom.window.KeyboardEvent("keydown", {
    key,
    bubbles: true,
    cancelable: true,
    ...init,
  });
  target.dispatchEvent(event);
  return event;
}

function editorElements() {
  const { document } = dom.window;
  return {
    editor: document.querySelector<HTMLElement>("#editor")!,
    surface: document.querySelector<HTMLElement>("#editor .ProseMirror")!,
    status: document.querySelector<HTMLElement>("#editor [role=status]")!,
    toolbar: document.querySelector<HTMLElement>("#editor [role=toolbar]")!,
    help: document.querySelector<HTMLDialogElement>("#editor dialog.docweave-help")!,
  };
}

/** A snapshot whose current document differs from its generated one. */
function snapshotWith(
  docweave: Docweave,
  adjust: (current: Record<string, unknown>) => void,
) {
  const headless = docweave.createDocEditor();
  headless.render(buildTarget(docweave));
  const snapshot = headless.getSnapshot();
  adjust(snapshot.current);
  return snapshot;
}

describe("editing surface", () => {
  it("is a named multiline text box, named Document unless told otherwise", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor", label: "Order" });
    const { surface } = editorElements();
    assert.equal(surface.getAttribute("role"), "textbox");
    assert.equal(surface.getAttribute("aria-multiline"), "true");
    assert.equal(surface.getAttribute("aria-label"), "Order");
    controller.destroy();

    const unnamed = docweave.createDocEditor({ mount: "#editor" });
    assert.equal(editorElements().surface.getAttribute("aria-label"), "Document");
    unnamed.destroy();

    const blank = docweave.createDocEditor({ mount: "#editor", label: "  " });
    assert.equal(editorElements().surface.getAttribute("aria-label"), "Document", "a blank label is no name");
    blank.destroy();
  });

  it("marks a fact as a generated field that details its source control", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(buildTarget(docweave));
    const fact = editorElements().surface.querySelector("[data-generated-text]")!;
    assert.equal(fact.getAttribute("role"), "link");
    assert.equal(fact.getAttribute("aria-roledescription"), "generated field");
    assert.equal(fact.getAttribute("aria-details"), "deadline");
    assert.equal(fact.getAttribute("aria-description"), "Possession deadline", "named after the page's label");
    assert.equal(fact.textContent, "1 October 2026");
    controller.destroy();
  });

  it("prefers a fact's own label, and names a radio after its question", async () => {
    const docweave = await import("../src/index.js");
    dom.window.document.body.insertAdjacentHTML("beforeend", `
      <fieldset id="when"><legend>When must possession be given?</legend>
        <input type="radio" id="when-now" name="when" checked><label for="when-now">Forthwith</label>
      </fieldset>
      <label for='odd"]id'>Oddly identified</label><input id='odd"]id'>`);
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(docweave.buildDoc((doc) => {
      doc.paragraph("possession", (content) => {
        content
          .fact("deadline", "1 October 2026", { sourceId: "deadline", label: "Deadline for possession" })
          .text(" or ")
          .fact("when", "forthwith", { sourceId: "when-now" })
          .text(" ")
          .fact("plain", "unlabelled")
          .text(" ")
          .fact("odd", "x", { sourceId: 'odd"]id' });
      });
    }));
    const facts = [...editorElements().surface.querySelectorAll("[data-generated-text]")];
    assert.equal(facts[0]!.getAttribute("aria-description"), "Deadline for possession");
    assert.equal(facts[1]!.getAttribute("aria-description"), "When must possession be given?");
    assert.equal(facts[2]!.getAttribute("aria-description"), null);
    assert.equal(facts[2]!.getAttribute("role"), null, "no source, so nothing to go to");
    assert.equal(facts[3]!.getAttribute("aria-description"), "Oddly identified", "an ID that is not selector-safe");
    controller.destroy();
  });

  it("announces an edit the invariants refuse, leaving the document as it was", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(buildTarget(docweave));
    const { surface, status } = editorElements();
    const before = controller.getSnapshot().current;
    assert.equal(status.getAttribute("aria-live"), "polite");
    assert.equal(status.textContent, "");

    surface.focus();
    keydown(surface, "a", { ctrlKey: true });
    keydown(surface, "Backspace");

    assert.equal(status.textContent, BLOCKED_EDIT_MESSAGE);
    assert.deepEqual(controller.getSnapshot().current, before);
    controller.destroy();
  });
});

describe("clause markers and reverting", () => {
  it("speaks an inserted clause and removes it from the keyboard shortcut", async () => {
    const docweave = await import("../src/index.js");
    const initialSnapshot = snapshotWith(docweave, (current) => {
      (current.content as unknown[]).unshift({
        type: "paragraph",
        attrs: { id: null },
        content: [{ type: "text", text: "My own clause." }],
      });
    });
    const controller = docweave.createDocEditor({ mount: "#editor", initialSnapshot });
    const { surface, status } = editorElements();
    const marker = surface.querySelector(".docweave-editor__clause-marker")!;
    assert.equal(marker.textContent, "Inserted clause. ");
    assert.equal(
      marker.querySelector("button")!.getAttribute("aria-label"),
      "Undo inserted clause",
    );

    surface.focus();
    keydown(surface, "z", { ctrlKey: true, altKey: true });

    assert.equal(status.textContent, "Inserted clause removed.");
    assert.deepEqual(controller.getSnapshot().current, initialSnapshot.generated);
    assert.equal(surface.querySelector(".docweave-editor__clause-marker"), null);
    controller.destroy();
  });

  it("restores a modified clause from its gutter button by keyboard", async () => {
    const docweave = await import("../src/index.js");
    const initialSnapshot = snapshotWith(docweave, (current) => {
      const heading = (current.content as Array<{ content: Array<{ text: string }> }>)[0]!;
      heading.content[0]!.text = "IT IS ORDERED THAT (amended):";
    });
    const controller = docweave.createDocEditor({ mount: "#editor", initialSnapshot });
    const { surface, status } = editorElements();
    const marker = surface.querySelector(".docweave-editor__clause-marker")!;
    assert.equal(marker.textContent, "Modified clause. ");
    const gutterButton = marker.querySelector("button")!;
    assert.equal(gutterButton.getAttribute("aria-label"), "Undo changes to clause");

    gutterButton.focus();
    const event = keydown(gutterButton, "Enter");

    assert.equal(event.defaultPrevented, true);
    assert.equal(status.textContent, "Clause restored to its generated wording.");
    assert.deepEqual(controller.getSnapshot().current, initialSnapshot.generated);
    assert.equal(dom.window.document.activeElement, surface);
    controller.destroy();
  });

  it("does nothing outside an inserted or modified clause", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(buildTarget(docweave));
    const { surface, status } = editorElements();
    const before = controller.getSnapshot().current;

    surface.focus();
    keydown(surface, "z", { ctrlKey: true, altKey: true });

    assert.equal(status.textContent, "");
    assert.deepEqual(controller.getSnapshot().current, before);
    controller.destroy();
  });
});

describe("toolbar and keyboard help", () => {
  it("is one Tab stop reached by Alt+F10, with the arrow keys moving between buttons", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(buildTarget(docweave));
    const { surface, toolbar } = editorElements();
    const { document } = dom.window;
    const buttons = [...toolbar.querySelectorAll<HTMLButtonElement>("button")];
    const tabbable = () => buttons.filter((button) => button.tabIndex === 0);
    assert.equal(tabbable().length, 1);
    assert.equal(tabbable()[0]!.disabled, false);

    surface.focus();
    assert.equal(keydown(surface, "F10", { altKey: true }).defaultPrevented, true);
    const first = document.activeElement as HTMLButtonElement;
    assert.ok(buttons.includes(first));
    assert.equal(first, tabbable()[0]);

    keydown(first, "ArrowRight");
    const second = document.activeElement as HTMLButtonElement;
    assert.notEqual(second, first);
    assert.ok(buttons.includes(second));
    assert.equal(second.disabled, false);
    assert.deepEqual(tabbable(), [second]);

    keydown(second, "End");
    assert.equal(document.activeElement!.getAttribute("aria-label"), "Keyboard shortcuts");
    keydown(document.activeElement!, "ArrowRight");
    assert.equal(document.activeElement, first, "the arrows wrap around");
    controller.destroy();
  });

  it("lists the shortcuts on Alt+0 and returns focus to the document when closed", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(buildTarget(docweave));
    const { surface, help } = editorElements();
    const { document } = dom.window;
    assert.equal(help.open, false);

    surface.focus();
    keydown(surface, "0", { altKey: true, code: "Numpad0" });
    assert.equal(help.open, false, "a Windows Alt code must not open it");
    assert.equal(keydown(surface, "0", { altKey: true, code: "Digit0" }).defaultPrevented, true);

    assert.equal(help.open, true);
    assert.equal(help.getAttribute("aria-labelledby"), help.querySelector("h2")!.id);
    const rows = [...help.querySelectorAll("tbody th")].map((cell) => cell.textContent);
    assert.ok(rows.includes("Ctrl+Alt+Z"), rows.join(", "));
    assert.ok(rows.includes("Alt+F10"));
    assert.ok(!rows.some((row) => row?.startsWith("/")), "no template shortcut without templates");
    assert.ok(help.contains(document.activeElement));

    help.querySelector<HTMLButtonElement>(".docweave-help__close")!.click();
    assert.equal(help.open, false);
    assert.equal(document.activeElement, surface);

    const helpButton = document.querySelector<HTMLButtonElement>('[aria-label="Keyboard shortcuts"]')!;
    helpButton.focus();
    helpButton.click();
    assert.equal(help.open, true);
    keydown(help, "Escape");
    help.close();
    assert.equal(document.activeElement, helpButton, "back to the toolbar button that opened it");

    helpButton.click();
    assert.equal(help.open, true);
    controller.destroy();
    assert.equal(document.querySelector("dialog.docweave-help"), null);
  });

  it("announces a burst of the same message once", async () => {
    const { createAnnouncer } = await import("../src/announcer.js");
    const announcer = createAnnouncer(dom.window.document);
    announcer.announce("Refused.");
    announcer.announce("Refused.");
    announcer.announce("Refused.");
    assert.equal(announcer.element.textContent, "Refused.");
    announcer.announce("Something else.");
    assert.equal(announcer.element.textContent, "Something else.");
    announcer.destroy();
  });
});

describe("return journey from a source control", () => {
  const returnButton = () =>
    dom.window.document.querySelector<HTMLButtonElement>(".docweave-editor__return");

  async function leaveForInput() {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(buildTarget(docweave));
    const { surface, status } = editorElements();
    const { document } = dom.window;
    const input = document.querySelector<HTMLInputElement>("#deadline")!;
    const fact = () => surface.querySelector<HTMLElement>("[data-generated-text]")!;

    fact().focus();
    keydown(fact(), "Enter");
    assert.equal(document.activeElement, input);
    const button = returnButton()!;
    assert.equal(button.textContent, "Return to document");
    assert.equal(input.nextElementSibling, button);
    return { docweave, controller, surface, status, input, button, fact };
  }

  it("offers a button beside the input that returns to the fact with its new value", async () => {
    const { docweave, controller, surface, status, input, button, fact } = await leaveForInput();
    const { document } = dom.window;

    input.value = "2 October 2026";
    controller.render(docweave.buildDoc((doc) => {
      doc.paragraph("heading", "IT IS ORDERED THAT:");
      doc.paragraph("possession", (content) => {
        content
          .text("The defendant must give up possession by ")
          .fact("deadline", input.value, { sourceId: "deadline" })
          .text(".");
      });
    }));
    button.click();

    assert.equal(document.activeElement, surface);
    assert.ok(fact().classList.contains("ProseMirror-selectednode"));
    assert.equal(fact().textContent, "2 October 2026");
    assert.equal(status.textContent, "Returned to Possession deadline, 2 October 2026.");
    assert.equal(returnButton(), null, "the offer is withdrawn once used");
    assert.ok(surface.contains(document.activeElement));
    controller.destroy();
  });

  it("returns from anywhere on the page with the shortcut", async () => {
    const { controller, surface, status, input, fact } = await leaveForInput();
    const { document } = dom.window;

    const event = keydown(input, "d", { ctrlKey: true, altKey: true });

    assert.equal(event.defaultPrevented, true);
    assert.equal(document.activeElement, surface);
    assert.ok(fact().classList.contains("ProseMirror-selectednode"));
    assert.equal(status.textContent, "Returned to Possession deadline, 1 October 2026.");
    assert.equal(returnButton(), null);
    assert.equal(keydown(input, "d", { ctrlKey: true, altKey: true }).defaultPrevented, false, "nothing to return to");
    controller.destroy();
  });

  it("keeps the offer while focus stays with the control, and withdraws it once the reader moves on", async () => {
    const { controller, surface, input, button, fact } = await leaveForInput();
    const { document } = dom.window;

    button.focus();
    assert.equal(returnButton(), button, "the button itself is part of the offer");
    input.focus();
    assert.equal(returnButton(), button);

    document.querySelector<HTMLInputElement>("#other")!.focus();
    assert.equal(returnButton(), null, "another control ends it");
    assert.equal(keydown(document.body, "d", { ctrlKey: true, altKey: true }).defaultPrevented, false);

    fact().focus();
    keydown(fact(), "Enter");
    assert.equal(returnButton()?.textContent, "Return to document");
    surface.focus();
    assert.equal(returnButton(), null, "coming back to the editor ends it");

    fact().focus();
    keydown(fact(), "Enter");
    assert.ok(returnButton());
    controller.destroy();
    assert.equal(returnButton(), null);
  });

  it("makes no offer when the source has nothing to focus", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    controller.render(docweave.buildDoc((doc) => {
      doc.paragraph("possession", (content) => {
        content.text("See ").fact("note", "the note", { sourceId: "static" });
      });
    }));
    const { surface } = editorElements();
    const fact = surface.querySelector<HTMLElement>("[data-generated-text]")!;
    fact.focus();
    keydown(fact, "Enter");
    assert.equal(returnButton(), null);
    assert.equal(dom.window.document.activeElement, fact);
    controller.destroy();
  });
});

describe("walking the fields and hearing the document change", () => {
  async function twoFieldEditor() {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor", label: "Order" });
    const render = (deadline: string, other = "£300") =>
      controller.render(docweave.buildDoc((doc) => {
        doc.paragraph("heading", "IT IS ORDERED THAT:");
        doc.paragraph("possession", (content) => {
          content
            .text("Possession by ")
            .fact("deadline", deadline, { sourceId: "deadline" })
            .text(" and costs of ")
            .fact("costs", other, { sourceId: "other" })
            .text(".");
        });
      }));
    render("1 October 2026");
    const { surface, status } = editorElements();
    return { controller, render, surface, status };
  }

  it("moves between fields from the keyboard and says which field it is", async () => {
    const { controller, surface, status } = await twoFieldEditor();
    surface.focus();
    const next = () => keydown(surface, "ArrowDown", { altKey: true, shiftKey: true });
    const previous = () => keydown(surface, "ArrowUp", { altKey: true, shiftKey: true });
    const selected = () => surface.querySelector(".ProseMirror-selectednode")?.textContent;

    // The first render leaves the cursor at the end, so next wraps to the start.
    assert.equal(next().defaultPrevented, true);
    assert.equal(selected(), "1 October 2026");
    assert.equal(status.textContent, "Possession deadline, 1 October 2026. Field 1 of 2.");
    next();
    assert.equal(selected(), "£300");
    assert.equal(status.textContent, "Another answer, £300. Field 2 of 2.");
    next();
    assert.equal(selected(), "1 October 2026", "wraps around");
    previous();
    assert.equal(selected(), "£300", "and back the other way");
    assert.equal(status.textContent, "Another answer, £300. Field 2 of 2.");
    previous();
    assert.equal(selected(), "1 October 2026");
    controller.destroy();
  });

  it("announces what a re-render changed, and stays quiet when nothing did", async () => {
    const { controller, render, status } = await twoFieldEditor();
    assert.equal(status.textContent, "", "the first render is not a change");

    render("1 October 2026");
    assert.equal(status.textContent, "");

    render("2 October 2026");
    assert.equal(status.textContent, "Order updated: Possession deadline is now 2 October 2026.");

    render("3 October 2026", "£450");
    assert.equal(
      status.textContent,
      "Order updated: 2 fields changed. Possession deadline is now 3 October 2026.",
    );
    controller.destroy();
  });

  it("announces a document that changed shape without any field changing", async () => {
    const docweave = await import("../src/index.js");
    const controller = docweave.createDocEditor({ mount: "#editor" });
    const render = (costs: boolean) =>
      controller.render(docweave.buildDoc((doc) => {
        doc.paragraph("heading", "IT IS ORDERED THAT:");
        if (costs) doc.paragraph("costs", "Costs in the case.");
      }));
    render(false);
    render(true);
    assert.equal(editorElements().status.textContent, "Document updated.");
    controller.destroy();
  });
});
