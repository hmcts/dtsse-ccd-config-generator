export type HeaderEnhancementOptions = {
  root?: ParentNode;
};

export function initUiComponentLibEnhancements(
  _options: HeaderEnhancementOptions = {}
): void {
  // Client enhancement will be added after the SSR and parity baseline is in place.
}

export const initXuiHeaderEnhancements = initUiComponentLibEnhancements;
