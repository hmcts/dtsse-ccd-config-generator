import { type Command } from "prosemirror-state";
import { type EditorView } from "prosemirror-view";

import { createRedoIcon, createUndoIcon } from "./icons.js";

export interface EditorToolbarCommands {
  undo: Command;
  redo: Command;
  bold: Command;
  italic: Command;
  numbered: Command;
  outdent: Command;
  indent: Command;
}

type EditorCommandName = keyof EditorToolbarCommands;

function isEditorCommandName(value: string): value is EditorCommandName {
  return [
    "undo",
    "redo",
    "bold",
    "italic",
    "numbered",
    "outdent",
    "indent",
  ].includes(value);
}

function createToolbarButton(
  ownerDocument: Document,
  command: EditorCommandName,
  label: string,
  content: string | Node,
): HTMLButtonElement {
  const button = ownerDocument.createElement("button");
  button.type = "button";
  button.className = "docweave-editor__toolbar-button";
  button.dataset.editorCommand = command;
  button.setAttribute("aria-label", label);
  button.append(content);
  return button;
}

export function createEditorToolbar(
  ownerDocument: Document,
  label: string,
): HTMLElement {
  const toolbar = ownerDocument.createElement("div");
  toolbar.className = "docweave-editor__toolbar";
  toolbar.setAttribute("role", "toolbar");
  toolbar.setAttribute("aria-label", label);

  const iconClass = "docweave-editor__toolbar-icon";
  toolbar.append(
    createToolbarButton(
      ownerDocument,
      "undo",
      "Undo",
      createUndoIcon(ownerDocument, iconClass),
    ),
    createToolbarButton(
      ownerDocument,
      "redo",
      "Redo",
      createRedoIcon(ownerDocument, iconClass),
    ),
  );

  const separator = ownerDocument.createElement("span");
  separator.className = "docweave-editor__toolbar-separator";
  separator.setAttribute("aria-hidden", "true");
  toolbar.append(separator);

  const strong = ownerDocument.createElement("strong");
  strong.textContent = "B";
  const emphasis = ownerDocument.createElement("em");
  emphasis.textContent = "I";

  toolbar.append(
    createToolbarButton(ownerDocument, "bold", "Bold", strong),
    createToolbarButton(ownerDocument, "italic", "Italic", emphasis),
    createToolbarButton(ownerDocument, "numbered", "Numbered clause", "1."),
    createToolbarButton(ownerDocument, "outdent", "Outdent paragraph", "←"),
    createToolbarButton(ownerDocument, "indent", "Indent paragraph", "→"),
  );

  return toolbar;
}

export interface ConnectedEditorToolbar {
  update(): void;
  /** Moves focus into the toolbar, onto its current button. */
  focus(): void;
  destroy(): void;
}

export function connectEditorToolbar(
  toolbar: HTMLElement,
  view: EditorView,
  commands: EditorToolbarCommands,
): ConnectedEditorToolbar {
  const buttons = toolbar.querySelectorAll<HTMLButtonElement>(
    "[data-editor-command]",
  );
  // Every button, including any the caller appended, takes part in the
  // toolbar pattern: one Tab stop, with the arrow keys moving between buttons.
  const allButtons = [...toolbar.querySelectorAll<HTMLButtonElement>("button")];
  let current = allButtons[0];

  function enabledButtons(): HTMLButtonElement[] {
    return allButtons.filter((button) => !button.disabled);
  }

  function setCurrent(button: HTMLButtonElement | undefined): void {
    current = button;
    for (const candidate of allButtons) {
      candidate.tabIndex = candidate === current ? 0 : -1;
    }
  }

  function update(): void {
    for (const button of buttons) {
      const commandName = button.dataset.editorCommand;
      const enabled = commandName !== undefined &&
        isEditorCommandName(commandName) &&
        commands[commandName](view.state);
      button.disabled = !enabled;
    }
    const enabled = enabledButtons();
    setCurrent(
      current && !current.disabled ? current : enabled[0] ?? allButtons[0],
    );
  }

  const handleKeyDown = (event: KeyboardEvent): void => {
    const enabled = enabledButtons();
    const index = enabled.findIndex((button) => button === event.target);
    if (index === -1 || enabled.length === 0) return;
    const moves: Record<string, number> = {
      ArrowRight: index + 1,
      ArrowLeft: index - 1,
      Home: 0,
      End: enabled.length - 1,
    };
    const next = moves[event.key];
    if (next === undefined) return;
    event.preventDefault();
    const target = enabled[(next + enabled.length) % enabled.length]!;
    setCurrent(target);
    target.focus();
  };

  const handleMouseDown = (event: MouseEvent): void => {
    if (event.target instanceof Element &&
      event.target.closest("[data-editor-command]")) {
      event.preventDefault();
    }
  };

  const handleClick = (event: MouseEvent): void => {
    if (!(event.target instanceof Element)) return;

    const button = event.target.closest<HTMLButtonElement>(
      "[data-editor-command]",
    );
    const commandName = button?.dataset.editorCommand;
    if (!button || button.disabled || commandName === undefined ||
      !isEditorCommandName(commandName)) return;

    view.focus();
    commands[commandName](view.state, view.dispatch, view);
  };

  toolbar.addEventListener("mousedown", handleMouseDown);
  toolbar.addEventListener("click", handleClick);
  toolbar.addEventListener("keydown", handleKeyDown);
  update();

  return {
    update,
    focus(): void {
      current?.focus();
    },
    destroy(): void {
      toolbar.removeEventListener("mousedown", handleMouseDown);
      toolbar.removeEventListener("click", handleClick);
      toolbar.removeEventListener("keydown", handleKeyDown);
    },
  };
}
