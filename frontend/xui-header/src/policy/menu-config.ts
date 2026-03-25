import {
  AAT_MENU_DIFFERENCES,
  DEFAULT_MENU_CONFIG
} from './defaults.js';
import type {
  HeaderEnvironment,
  HeaderMenuConfig,
  HeaderNavItem
} from './types.js';

const AAT_LIKE_ENVIRONMENTS = new Set<HeaderEnvironment>(['local', 'aat', 'preview']);

export function resolveMenuConfig(
  environment: HeaderEnvironment = 'prod',
  menuConfig: HeaderMenuConfig = DEFAULT_MENU_CONFIG
): HeaderMenuConfig {
  if (!AAT_LIKE_ENVIRONMENTS.has(environment)) {
    return cloneMenuConfig(menuConfig);
  }

  return mergeMenuConfigs(menuConfig, AAT_MENU_DIFFERENCES);
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
): HeaderNavItem[] {
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

function cloneMenuItems(items: HeaderNavItem[]): HeaderNavItem[] {
  return items.map(cloneMenuItem);
}

function cloneMenuItem(item: HeaderNavItem): HeaderNavItem {
  return {
    ...item,
    ...(item.classes ? { classes: [...item.classes] } : {}),
    ...(item.roles ? { roles: [...item.roles] } : {}),
    ...(item.notRoles ? { notRoles: [...item.notRoles] } : {}),
    ...(item.flags ? { flags: [...item.flags] } : {}),
    ...(item.notFlags ? { notFlags: [...item.notFlags] } : {})
  };
}
