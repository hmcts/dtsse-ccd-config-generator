import { DEFAULT_THEME_CONFIGS } from './defaults.js';
import type { HeaderContext, HeaderThemeConfig } from './types.js';

export function resolveTheme(
  context: Pick<HeaderContext, 'user'>
): HeaderThemeConfig {
  const matchedTheme = DEFAULT_THEME_CONFIGS.find((themeConfig) => {
    const rolePattern = new RegExp(themeConfig.rolePattern);
    return context.user.roles.some((role) => rolePattern.test(role));
  });

  const fallbackTheme = DEFAULT_THEME_CONFIGS[DEFAULT_THEME_CONFIGS.length - 1];
  if (!fallbackTheme) {
    throw new Error('DEFAULT_THEME_CONFIGS must contain at least one theme');
  }

  return matchedTheme ?? fallbackTheme;
}
