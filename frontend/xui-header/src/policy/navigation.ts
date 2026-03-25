import {
  DEFAULT_CASE_MANAGER_ROLES,
  DEFAULT_MENU_FLAGS,
  NAV_HIDDEN_ROUTE_FRAGMENTS
} from './defaults.js';
import type {
  HeaderFlagDefinition,
  HeaderNavItem
} from './types.js';

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

export function filterNavItems(
  items: HeaderNavItem[],
  roles: string[],
  features: Record<string, boolean | string> = DEFAULT_MENU_FLAGS
): HeaderNavItem[] {
  return items.filter((item) => {
    const includesRole = matchesIncludedRoles(item, roles);
    const excludesRole = matchesExcludedRoles(item, roles);
    const includesFlags = matchesIncludedFlags(item, features);
    const excludesFlags = matchesExcludedFlags(item, features);

    return includesRole && excludesRole && includesFlags && excludesFlags;
  });
}

export function setActiveNavItems(
  items: HeaderNavItem[],
  currentPath: string
): HeaderNavItem[] {
  const [fullUrl, matchingUrl] = checkTabs(items, currentPath);

  return items.map((item) => ({
    ...item,
    active: fullUrl ? item.href === currentPath : item.href === matchingUrl
  }));
}

export function decorateRightNavItemsForSearch(
  items: HeaderNavItem[],
  roles: string[],
  features: Record<string, boolean | string> = DEFAULT_MENU_FLAGS
): HeaderNavItem[] {
  const globalSearchEnabled = Boolean(features['feature-global-search']);
  const caseManagerRole = isCaseManagerRole(roles);

  return items.map((item) => ({
    ...item,
    kind:
      item.align === 'right' && globalSearchEnabled && !caseManagerRole
        ? 'case-reference-search'
        : 'link',
    id: item.id ?? buildNavItemId(item)
  }));
}

export function withDerivedNavItemIds(items: HeaderNavItem[]): HeaderNavItem[] {
  return items.map((item) => ({
    ...item,
    id: item.id ?? buildNavItemId(item)
  }));
}

function checkTabs(items: HeaderNavItem[], currentPath: string): [boolean, string] {
  let fullUrl = false;
  let maxLength = 0;
  let matchingUrl = '';

  for (const item of items) {
    const checkHrefs = item.href === '/tasks'
      ? ['/tasks/list', '/tasks/available']
      : [item.href ?? ''];

    fullUrl = isFullUrl(item.href ?? '', currentPath);
    if (fullUrl) {
      break;
    }

    if (item.href === '/cases') {
      continue;
    }

    if (checkHrefs.some((url) => currentPath.indexOf(url) === 0)) {
      if ((item.href?.length ?? 0) > maxLength) {
        maxLength = item.href?.length ?? 0;
        matchingUrl = item.href ?? '';
      }
    }
  }

  return [fullUrl, matchingUrl];
}

function isFullUrl(href: string, currentPath: string): boolean {
  return href === currentPath || currentPath === '/cases/case-search';
}

function matchesIncludedRoles(item: HeaderNavItem, roles: string[]): boolean {
  if (!item.roles || item.roles.length === 0) {
    return true;
  }

  return item.roles.some((role) => roles.includes(role));
}

function matchesExcludedRoles(item: HeaderNavItem, roles: string[]): boolean {
  if (!item.notRoles || item.notRoles.length === 0) {
    return true;
  }

  return item.notRoles.every((role) => !roles.includes(role));
}

function matchesIncludedFlags(
  item: HeaderNavItem,
  features: Record<string, boolean | string>
): boolean {
  if (!item.flags || item.flags.length === 0) {
    return true;
  }

  return item.flags.every((flag) => evaluateFlag(flag, features));
}

function matchesExcludedFlags(
  item: HeaderNavItem,
  features: Record<string, boolean | string>
): boolean {
  if (!item.notFlags || item.notFlags.length === 0) {
    return true;
  }

  return item.notFlags.every((flag) => !evaluateFlag(flag, features));
}

function evaluateFlag(
  flag: HeaderFlagDefinition,
  features: Record<string, boolean | string>
): boolean {
  if (typeof flag === 'string') {
    return Boolean(features[flag]);
  }

  return features[flag.flagName] === flag.value;
}

function buildNavItemId(item: HeaderNavItem): string {
  const textPart = item.text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  const hrefPart = (item.href ?? 'action')
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();

  return `${textPart || 'item'}-${hrefPart || 'action'}`;
}
