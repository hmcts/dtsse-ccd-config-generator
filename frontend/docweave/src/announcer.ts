/**
 * A polite live region for what the editor does that a screen reader cannot
 * otherwise tell: an edit the invariants refused, a clause reverted, a document
 * that changed under the reader.
 */
export interface Announcer {
  readonly element: HTMLElement;
  announce(message: string): void;
  destroy(): void;
}

const REPEAT_WINDOW_MS = 1000;

export function createAnnouncer(ownerDocument: Document): Announcer {
  const element = ownerDocument.createElement("div");
  element.className =
    "docweave-editor__status docweave-editor__visually-hidden";
  element.setAttribute("role", "status");
  element.setAttribute("aria-live", "polite");
  element.setAttribute("aria-atomic", "true");

  let pending: ReturnType<typeof setTimeout> | undefined;
  let last: { message: string; at: number } | undefined;

  return {
    element,
    announce(message) {
      // A held key refuses the same edit many times a second; once is enough.
      const now = Date.now();
      if (last?.message === message && now - last.at < REPEAT_WINDOW_MS) return;
      last = { message, at: now };

      clearTimeout(pending);
      if (element.textContent !== message) {
        element.textContent = message;
        return;
      }
      // A live region only speaks when its content changes, so a repeat is
      // cleared first and set again once the clearing has been observed.
      element.textContent = "";
      pending = setTimeout(() => {
        element.textContent = message;
      }, 50);
    },
    destroy() {
      clearTimeout(pending);
      element.remove();
    },
  };
}
