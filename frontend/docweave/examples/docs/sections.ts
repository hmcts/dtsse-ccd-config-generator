/**
 * The interactive documentation, one section per concept. Each section's code
 * is shown in an editable block and evaluated in the page; the same code is run
 * by the tests so the documentation cannot drift from the library.
 */

export interface CheckboxInput {
  kind: "checkbox";
  name: string;
  label: string;
  checked: boolean;
}

export interface TextInput {
  kind: "text";
  name: string;
  label: string;
  value: string;
}

export type SectionInput = CheckboxInput | TextInput;

export type InputValues = Record<string, string | boolean>;

interface SectionBase {
  id: string;
  title: string;
  /** Paragraphs of explanation shown before the code. */
  prose: readonly string[];
  /** Things to try after the code has run. */
  tryThis: readonly string[];
  code: string;
  inputs: readonly SectionInput[];
}

/**
 * The code is the body of `(buildDoc, inputs) => DocWeaveDocument`. The page
 * owns the editor and re-renders whenever the code or an input changes.
 */
export interface BuildSection extends SectionBase {
  kind: "build";
  /** Show the snapshot that keeps the document's structure beside the HTML. */
  showSnapshot?: boolean;
}

/**
 * The code is the body of `(docweave, mount, saved, provider) => controller`.
 * It runs when the reader presses Run and must return the controller it made so
 * the page can destroy it before the next run.
 */
export interface ScriptSection extends SectionBase {
  kind: "script";
}

export type DocsSection = BuildSection | ScriptSection;

export const BUILD_PARAMETERS = ["buildDoc", "inputs"] as const;
export const SCRIPT_PARAMETERS = [
  "docweave",
  "mount",
  "saved",
  "provider",
] as const;

export function defaultInputValues(
  inputs: readonly SectionInput[],
): InputValues {
  return Object.fromEntries(
    inputs.map((input) => [
      input.name,
      input.kind === "checkbox" ? input.checked : input.value,
    ]),
  );
}

/** The DOM id of an input control, which facts use as their `sourceId`. */
export function inputControlId(sectionId: string, inputName: string): string {
  return `${sectionId}-${inputName}`;
}

export const sections: readonly DocsSection[] = [
  {
    id: "first-document",
    kind: "build",
    title: "Document generation",
    prose: [
      "Documents are built in code.",
      "The code below is the body of a function that receives buildDoc and must return the document it builds. Edit it and the editor on the right updates.",
      "Unique clause IDs are how Docweave recognises the same clause from one build to the next.",
    ],
    tryThis: [
      "Change the wording in the code. The clause in the editor is replaced with the new wording.",
      "Now edit a clause in the editor instead. Your edit stays until the code for that clause changes.",
      "Give both paragraphs the same ID. Docweave rejects the document and says why.",
    ],
    code: `return buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.paragraph("biscuits", "Biscuits shall be served.");
});
`,
    inputs: [],
  },
  {
    id: "structure",
    kind: "build",
    showSnapshot: true,
    title: "What comes out",
    prose: [
      "Two things come out of the editor. Both come from the snapshot, which is what you store.",
      "1. Plain HTML: the document as the author left it, which is what you render as their final document. renderHtml produces it from the snapshot.",
      "2. A structured representation as JSON, for tracking user edits. Generated clauses keep their IDs and facts stay marked, while anything the author inserted carries no ID.",
    ],
    tryThis: [
      "Edit the biscuits clause. The HTML changes, and the paragraph in the snapshot keeps its ID.",
      "Put the cursor at the end of a clause, press Enter and type a clause of your own. In the snapshot it is the paragraph with no ID.",
      "Change the date in the code. The fact updates and your edits around it stay.",
    ],
    code: `return buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.paragraph("biscuits", "Biscuits shall be served.");
  doc.paragraph("deadline", (content) => {
    content
      .text("Tea shall be poured by ")
      .fact("date", "4pm on 1 October 2026")
      .text(".");
  });
});
`,
    inputs: [],
  },
  {
    id: "inputs",
    kind: "build",
    title: "Reacting to inputs",
    prose: [
      "The point of building the document in code is that it can depend on the answers to a form. When an answer changes, build the document again and render it. Docweave reconciles the new document with the one in the editor rather than replacing it.",
      "Here the inputs object holds the values of the controls beside the code.",
    ],
    tryThis: [
      "Edit the biscuits clause in the editor, then tick cake. Your edit survives because the biscuits clause kept its ID.",
      "Untick tea and tick it again. The clause comes back with its generated wording.",
    ],
    code: `return buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.paragraph("biscuits", "Biscuits shall be served.");
  if (inputs.cake) {
    doc.paragraph("cake", "Cake shall be served.");
  }
  if (inputs.tea) {
    doc.paragraph("tea", "Tea shall be poured.");
  }
});
`,
    inputs: [
      { kind: "checkbox", name: "cake", label: "Serve cake", checked: false },
      { kind: "checkbox", name: "tea", label: "Pour tea", checked: true },
    ],
  },
  {
    id: "facts",
    kind: "build",
    title: "Facts",
    prose: [
      "Some wording must not be edited: a date, an amount, an address. Declare it as a fact. Facts are shown as fixed text in the editor, and when the value changes the fact is updated in place while the wording around it, including the reader's edits, is kept.",
      "A fact can name the control that supplies its value with sourceId. Activating the fact in the editor moves focus to that control.",
    ],
    tryThis: [
      "Change the deadline. Only the fact changes; edit the words around it first to see them survive.",
      "Try to type inside a fact. The editor will not let you.",
      "Click the deadline in the editor. Focus moves to the deadline input.",
    ],
    code: `return buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.paragraph("possession", (content) => {
    content
      .text("The defendant must give up possession of ")
      .fact("address", inputs.address)
      .text(" on or before ")
      .fact("deadline", inputs.deadline, { sourceId: "facts-deadline" })
      .text(".");
  });
});
`,
    inputs: [
      {
        kind: "text",
        name: "address",
        label: "Property address",
        value: "10 Test Street, Bristol",
      },
      {
        kind: "text",
        name: "deadline",
        label: "Possession deadline",
        value: "1 October 2026",
      },
    ],
  },
  {
    id: "lists",
    kind: "build",
    title: "Numbered clauses",
    prose: [
      "Orders are mostly numbered clauses. An ordered list holds items, and an item may hold one nested ordered list. Items are clauses like paragraphs: they have an ID, and they may be edited but not deleted or reordered.",
      "The reader can add their own numbered clauses between the generated ones. Those are kept when the document is rebuilt.",
    ],
    tryThis: [
      "Untick costs. The clause goes and the numbering closes up.",
      "Put the cursor at the end of a clause, press Enter and type a new clause of your own. Then tick costs again: your clause stays where you put it.",
      "Try to delete a generated clause. Docweave keeps it.",
    ],
    code: `return buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.orderedList("clauses", (list) => {
    list.item("possession", "The defendant must give up possession.");
    if (inputs.costs) {
      list.item("costs", "The defendant must pay the claimant's costs:", (item) => {
        item.orderedList("costs-terms", (terms) => {
          terms.item("costs-fixed", "fixed costs of £300;");
          terms.item("costs-when", "payable within 14 days.");
        });
      });
    }
  });
});
`,
    inputs: [
      { kind: "checkbox", name: "costs", label: "Order costs", checked: true },
    ],
  },
  {
    id: "accessibility",
    kind: "build",
    title: "Accessibility",
    prose: [
      "Docweave is used by judges who may read with a screen reader or work from the keyboard alone, so what the editor shows in colour it also says in words. The editing surface is a named text box. A fact is announced as a generated field with a name: the label or legend of its source control on the page, or the label option if you give one. Pressing Enter on a fact moves to the input that supplies its value.",
      "Alt+Shift+Down and Alt+Shift+Up walk the document field by field, announcing each field's name, value and position, and wrapping round at the end. When an input changes and the document is rebuilt, the editor says what changed: which field, and its new value.",
      "Leaving the document for an input is only half a journey. A Return to document button appears beside the input, and Ctrl+Alt+D works from anywhere on the page; either puts you back on the field you left and announces its value, which may have changed while you were away.",
      "A clause you insert or change is announced as such before its wording. When the invariants refuse an edit, the editor says so in a polite live region rather than silently keeping the clause.",
      "Alt+F10 moves to the toolbar, where the arrow keys move between buttons and Tab leaves it. Ctrl+Alt+Z, or Cmd+Alt+Z on a Mac, undoes your changes to the clause at the cursor or removes a clause you inserted. Alt+0 lists every shortcut.",
    ],
    tryThis: [
      "Press Alt+0 in the editor for the list of keyboard shortcuts, then close it: focus returns to where you were.",
      "Put the cursor at the start of the possession clause and press Backspace. Nothing is deleted, and a screen reader hears why.",
      "Press Enter at the end of a clause and type a clause of your own. It is marked as inserted; press Ctrl+Alt+Z inside it to remove it again.",
      "Press Enter on the deadline in the document. Focus moves to the deadline input, with a Return to document button beside it. Change the date, then press the button or Ctrl+Alt+D: you are back on the deadline, and hear its new value.",
      "Put the cursor anywhere in the document and press Alt+Shift+Down. The deadline is selected and announced as field 1 of 1.",
      "Change the deadline input. A screen reader hears that the document was updated and what the deadline is now.",
    ],
    code: `return buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.paragraph("possession", (content) => {
    content
      .text("The defendant must give up possession on or before ")
      .fact("deadline", inputs.deadline, {
        sourceId: "accessibility-deadline",
        label: "Possession deadline",
      })
      .text(".");
  });
});
`,
    inputs: [
      {
        kind: "text",
        name: "deadline",
        label: "Possession deadline",
        value: "1 October 2026",
      },
    ],
  },
  {
    id: "editor",
    kind: "script",
    title: "The editor and snapshots",
    prose: [
      "createDocEditor mounts an editor and returns a controller. Give it a label: that is the accessible name of the editing surface, read out when it receives focus. Call render with each new document. Call getSnapshot to get a serialisable record of the reader's document and the generated document it was reconciled against, or pass onChange to be given it whenever it changes. Pass a snapshot back as initialSnapshot to restore the editor later, or to load to switch the mounted editor to another document; load with no snapshot starts again from an empty one. The editor keeps its own input and change events from reaching the page's form. Call destroy when the editor is removed.",
      "The code below runs when you press Run. The page passes the previous run's snapshot as saved, so your edits survive the editor being destroyed and recreated. Without a mount, createDocEditor runs headlessly for tests and servers.",
    ],
    tryThis: [
      "Edit the document, then press Run. The new editor restores your edits.",
      "Remove initialSnapshot and press Run. The editor starts from the generated document.",
      "Look at the snapshot below the editor. It is plain JSON, safe to store with the case.",
    ],
    code: `const { createDocEditor, buildDoc } = docweave;

const controller = createDocEditor({
  mount,
  label: "Order",
  initialSnapshot: saved,
});

controller.render(buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.paragraph("biscuits", "Biscuits shall be served.");
}));

return controller;
`,
    inputs: [],
  },
  {
    id: "templates",
    kind: "script",
    title: "Saved templates",
    prose: [
      "Readers can save wording they use often as a template and insert it later. Enable the template library by giving the editor a provider. This page uses an in-memory provider; in an application, pass the same-origin URL of a template endpoint and a CSRF token instead, and serve that endpoint with createTemplateProxy from @hmcts-cft/docweave/express.",
    ],
    tryThis: [
      "Press Insert template in the toolbar, or type / on an empty line, and save the current wording as a template.",
      "Start a new empty line, type / and insert the template.",
    ],
    code: `const { createDocEditor, buildDoc } = docweave;

const controller = createDocEditor({
  mount,
  templates: { provider },
});

controller.render(buildDoc((doc) => {
  doc.paragraph("heading", "IT IS ORDERED THAT:");
  doc.paragraph("biscuits", "Biscuits shall be served.");
}));

return controller;
`,
    inputs: [],
  },
];

export const templatesInApplicationExample = `createDocEditor({
  mount: "#editor",
  templates: {
    url: "/docweave/templates",
    csrfToken: () => document.querySelector('input[name="_csrf"]')?.value,
  },
});

// On the server, behind your authentication middleware:
import { createTemplateProxy } from "@hmcts-cft/docweave/express";

app.use("/docweave/templates", createTemplateProxy({
  upstream: "https://your-api/docweave/templates",
  getUserToken: (request) => request.session.user?.accessToken,
  getServiceToken: () => serviceAuth.getToken(),
}));
`;
