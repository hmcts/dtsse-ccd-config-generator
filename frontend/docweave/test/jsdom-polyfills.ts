import { type DOMWindow } from "jsdom";

/**
 * jsdom implements neither the dialog API nor scrolling, so these stand in for
 * them. They live here so shipped code can call the real APIs unconditionally.
 */
export function polyfillBrowserApis(window: DOMWindow): void {
  window.HTMLDialogElement.prototype.showModal ??= function showModal(): void {
    this.setAttribute("open", "");
  };
  window.HTMLDialogElement.prototype.close ??= function close(): void {
    this.removeAttribute("open");
  };
  window.Element.prototype.scrollIntoView ??= function scrollIntoView(): void {};
}
