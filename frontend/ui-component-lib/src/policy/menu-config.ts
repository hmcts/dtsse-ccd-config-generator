import {
  AAT_MENU_DIFFERENCES,
  DEFAULT_MENU_CONFIG
} from './defaults.js';
import type {
  HeaderEnvironment,
  HeaderMenuConfig,
  HeaderMenuItem
} from './types.js';

export function resolveMenuConfig(environment: HeaderEnvironment): HeaderMenuConfig {
  switch (environment) {
    case 'prod':
      return cloneMenuConfig(DEFAULT_MENU_CONFIG);
    case 'aat':
      return mergeMenuConfigs(DEFAULT_MENU_CONFIG, AAT_MENU_DIFFERENCES);
    default:
      throw new Error(
        'HeaderContext.environment must be "prod" or "aat".'
      );
  }
}

function cloneMenuConfig(menuConfig: HeaderMenuConfig): HeaderMenuConfig {
  return Object.fromEntries(
    Object.entries(menuConfig).map(([rolePattern, items]) => [
      rolePattern,
      items.map(cloneMenuItem)
    ])
  );
}

function mergeMenuConfigs(
  baseConfig: HeaderMenuConfig,
  overlayConfig: HeaderMenuConfig
): HeaderMenuConfig {
  const mergedConfig = cloneMenuConfig(baseConfig);

  for (const [rolePattern, overlayItems] of Object.entries(overlayConfig)) {
    if (!mergedConfig[rolePattern]) {
      mergedConfig[rolePattern] = overlayItems.map((item) => ({ ...item }));
      continue;
    }

    for (const overlayItem of overlayItems) {
      const baseItem = mergedConfig[rolePattern]?.find(
        (item) => item.text === overlayItem.text && Array.isArray(item.roles)
      );

      if (!baseItem) {
        mergedConfig[rolePattern]?.push({ ...overlayItem });
        continue;
      }

      const mergedRoles = new Set([
        ...(baseItem.roles ?? []),
        ...(overlayItem.roles ?? [])
      ]);
      baseItem.roles = [...mergedRoles];
    }
  }

  return mergedConfig;
}

export function selectMenuItems(
  roles: string[],
  menuConfig: HeaderMenuConfig
): HeaderMenuItem[] {
  const defaultRolePattern = '.+';
  const otherPatterns = Object.keys(menuConfig).filter(
    (rolePattern) => rolePattern !== defaultRolePattern
  );

  const selectedPattern = otherPatterns.find((rolePattern) => {
    const matcher = new RegExp(rolePattern);
    return roles.some((role) => matcher.test(role));
  });

  return cloneMenuItems(
    menuConfig[selectedPattern ?? defaultRolePattern] ?? []
  );
}

function cloneMenuItems(items: HeaderMenuItem[]): HeaderMenuItem[] {
  return items.map(cloneMenuItem);
}

function cloneMenuItem(item: HeaderMenuItem): HeaderMenuItem {
  return {
    ...item,
    ...(item.roles ? { roles: [...item.roles] } : {}),
    ...(item.notRoles ? { notRoles: [...item.notRoles] } : {})
  };
}
