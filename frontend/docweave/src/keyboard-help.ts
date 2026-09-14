import { mac } from "./keymap.js";

export interface KeyboardShortcut {
  keys: string;
  description: string;
}

export interface KeyboardHelpDialog {
  readonly element: HTMLDialogElement;
  open(): void;
  destroy(): void;
}

let nextId = 0;

/** "Mod" reads as the platform's primary modifier. */
export function describeKeys(keys: string): string {
  return keys.replaceAll("Mod", mac ? "Cmd" : "Ctrl");
}

export function editorShortcuts(
  options: { templates: boolean },
): KeyboardShortcut[] {
  return [
    { keys: "Mod+B", description: "Bold" },
    { keys: "Mod+I", description: "Italic" },
    { keys: "Mod+Z", description: "Undo" },
    {
      keys: mac ? "Shift+Mod+Z" : "Mod+Y or Shift+Mod+Z",
      description: "Redo",
    },
    {
      keys: "Tab or Shift+Tab",
      description: "Indent or outdent a numbered clause",
    },
    {
      keys: "Enter, on a generated field",
      description: "Go to the input that supplies the field's value",
    },
    {
      keys: "Alt+Shift+Down or Alt+Shift+Up",
      description: "Go to the next or previous generated field",
    },
    {
      keys: "Mod+Alt+D",
      description:
        "Return to the generated field you left, from anywhere on the page",
    },
    {
      keys: "Mod+Alt+Z",
      description:
        "Undo your changes to the clause at the cursor, or remove a clause you inserted",
    },
    ...(options.templates
      ? [{
        keys: "/, on an empty line",
        description: "Insert a saved template",
      }]
      : []),
    { keys: "Alt+F10", description: "Move to the formatting toolbar" },
    { keys: "Alt+0", description: "Show these keyboard shortcuts" },
  ];
}

/**
 * @param fallbackFocus Where focus goes on close if the element that opened
 * the dialog is no longer there to take it.
 */
export function createKeyboardHelpDialog(
  ownerDocument: Document,
  shortcuts: readonly KeyboardShortcut[],
  fallbackFocus: () => void,
): KeyboardHelpDialog {
  const id = `docweave-help-${++nextId}`;
  const dialog = ownerDocument.createElement("dialog");
  dialog.className = "docweave-help";
  dialog.setAttribute("aria-labelledby", `${id}-heading`);

  const heading = ownerDocument.createElement("h2");
  heading.id = `${id}-heading`;
  heading.className = "docweave-help__heading";
  heading.textContent = "Keyboard shortcuts";

  const table = ownerDocument.createElement("table");
  table.className = "docweave-help__table";
  const head = table.createTHead().insertRow();
  for (const text of ["Keys", "Action"]) {
    const cell = ownerDocument.createElement("th");
    cell.scope = "col";
    cell.textContent = text;
    head.append(cell);
  }
  const body = table.createTBody();
  for (const shortcut of shortcuts) {
    const row = body.insertRow();
    const keys = ownerDocument.createElement("th");
    keys.scope = "row";
    keys.textContent = describeKeys(shortcut.keys);
    const description = row.insertCell();
    description.textContent = shortcut.description;
    row.append(keys, description);
  }

  const close = ownerDocument.createElement("button");
  close.type = "button";
  close.className = "docweave-help__close";
  close.textContent = "Close";
  close.addEventListener("click", () => dialog.close());

  dialog.append(heading, table, close);

  // Back to whatever opened the dialog: the "?" toolbar button keeps its
  // place in the toolbar, the shortcut goes back to the document.
  let opener: HTMLElement | undefined;
  const handleClose = (): void => {
    if (opener?.isConnected) opener.focus();
    else fallbackFocus();
    opener = undefined;
  };
  dialog.addEventListener("close", handleClose);

  return {
    element: dialog,
    open() {
      const active = ownerDocument.activeElement;
      opener = active instanceof HTMLElement ? active : undefined;
      dialog.showModal();
      close.focus();
    },
    destroy() {
      // The close event is queued, so it must not reach a destroyed editor.
      dialog.removeEventListener("close", handleClose);
      if (dialog.open) dialog.close();
      dialog.remove();
    },
  };
}
