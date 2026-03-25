import {
  DEFAULT_CASE_MANAGER_ROLES,
  NAV_HIDDEN_ROUTE_FRAGMENTS
} from './defaults.js';
import type { HeaderNavItem } from './types.js';

export function shouldShowPrimaryNav(path: string): boolean {
  return NAV_HIDDEN_ROUTE_FRAGMENTS.every((fragment) => !path.includes(fragment));
}

export function isCaseManagerRole(roles: string[]): boolean {
  return DEFAULT_CASE_MANAGER_ROLES.some((role) => roles.includes(role));
}

export function splitNavItems(items: HeaderNavItem[]): {
  leftItems: HeaderNavItem[];
  rightItems: HeaderNavItem[];
} {
  return {
    leftItems: items.filter((item) => item.align !== 'right'),
    rightItems: items.filter((item) => item.align === 'right')
  };
}
