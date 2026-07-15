import type { HeaderMenuItem } from './types.js';

export function splitNavItems(items: HeaderMenuItem[]): {
  leftItems: HeaderMenuItem[];
  rightItems: HeaderMenuItem[];
} {
  return {
    leftItems: items.filter((item) => item.align !== 'right'),
    rightItems: items.filter((item) => item.align === 'right')
  };
}

export function filterNavItems(
  items: HeaderMenuItem[],
  roles: string[]
): HeaderMenuItem[] {
  return items.filter((item) => {
    const includesRole = matchesIncludedRoles(item, roles);
    const excludesRole = matchesExcludedRoles(item, roles);

    return includesRole && excludesRole;
  });
}

function matchesIncludedRoles(item: HeaderMenuItem, roles: string[]): boolean {
  if (!item.roles || item.roles.length === 0) {
    return true;
  }

  return item.roles.some((role) => roles.includes(role));
}

function matchesExcludedRoles(item: HeaderMenuItem, roles: string[]): boolean {
  if (!item.notRoles || item.notRoles.length === 0) {
    return true;
  }

  return item.notRoles.every((role) => !roles.includes(role));
}
