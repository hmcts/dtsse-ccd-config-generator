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
 * The code is the body of `(buildOrder, inputs) => DocWeaveDocument`. The page
 * owns the editor and re-renders whenever the code or an input changes.
 */
export interface BuildSection extends SectionBase {
  kind: "build";
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

export const BUILD_PARAMETERS = ["buildOrder", "inputs"] as const;
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
    title: "Your first document",
    prose: [
      "A Docweave document is built in code. Each paragraph has an ID and its wording. The ID is how Docweave recognises the same clause from one build to the next, so it must be unique within the document.",
      "The code below is the body of a function that receives buildOrder and must return the document it builds. Edit it and the editor on the right updates.",
    ],
    tryThis: [
      "Change the wording in the code. The clause in the editor is replaced with the new wording.",
      "Now edit a clause in the editor instead. Your edit stays until the code for that clause changes.",
      "Give both paragraphs the same ID. Docweave rejects the document and says why.",
    ],
    code: `return buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.paragraph("biscuits", "Biscuits shall be served.");
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
    code: `return buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.paragraph("biscuits", "Biscuits shall be served.");
  if (inputs.cake) {
    order.paragraph("cake", "Cake shall be served.");
  }
  if (inputs.tea) {
    order.paragraph("tea", "Tea shall be poured.");
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
    code: `return buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.paragraph("possession", (content) => {
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
    code: `return buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.orderedList("clauses", (list) => {
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
    id: "editor",
    kind: "script",
    title: "The editor and snapshots",
    prose: [
      "createOrderEditor mounts an editor and returns a controller. Call render with each new document. Call getSnapshot to get a serialisable record of the reader's document and the generated document it was reconciled against, and pass it back as initialSnapshot to restore the editor later. Call destroy when the editor is removed.",
      "The code below runs when you press Run. The page passes the previous run's snapshot as saved, so your edits survive the editor being destroyed and recreated. Without a mount, createOrderEditor runs headlessly for tests and servers.",
    ],
    tryThis: [
      "Edit the document, then press Run. The new editor restores your edits.",
      "Remove initialSnapshot and press Run. The editor starts from the generated document.",
      "Look at the snapshot below the editor. It is plain JSON, safe to store with the case.",
    ],
    code: `const { createOrderEditor, buildOrder } = docweave;

const controller = createOrderEditor({
  mount,
  initialSnapshot: saved,
});

controller.render(buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.paragraph("biscuits", "Biscuits shall be served.");
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
    code: `const { createOrderEditor, buildOrder } = docweave;

const controller = createOrderEditor({
  mount,
  templates: { provider },
});

controller.render(buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.paragraph("biscuits", "Biscuits shall be served.");
}));

return controller;
`,
    inputs: [],
  },
];

export const templatesInApplicationExample = `createOrderEditor({
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
