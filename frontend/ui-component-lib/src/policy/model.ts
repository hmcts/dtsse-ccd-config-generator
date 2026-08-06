import {
  DEFAULT_ACCOUNT_ITEMS,
  DEFAULT_ACCOUNT_NAV_LABEL,
  DEFAULT_ASSETS_PATH,
  DEFAULT_SKIP_LINK
} from './defaults.js';
import {
  filterNavItems,
  splitNavItems
} from './navigation.js';
import { resolveMenuConfig, selectMenuItems } from './menu-config.js';
import { resolveTheme } from './theme.js';
import {
  normaliseRequiredBaseUrl,
  resolveUrl
} from '../urls.js';
import type {
  HeaderContext,
  HeaderMenuItem,
  HeaderModel,
  HeaderNavItem
} from './types.js';

export function buildHeaderModel(context: HeaderContext): HeaderModel {
  const xuiBaseUrl = normaliseRequiredBaseUrl(
    context.xuiBaseUrl,
    'HeaderContext.xuiBaseUrl'
  );
  const theme = resolveTheme(context);
  const menuConfig = resolveMenuConfig(context.environment ?? 'aat');

  let navItems = context.user.roles.length > 0
    ? selectMenuItems(context.user.roles, menuConfig)
    : [];
  navItems = filterNavItems(navItems, context.user.roles);
  const { leftItems, rightItems } = splitNavItems(navItems);

  return {
    assetsPath: DEFAULT_ASSETS_PATH,
    theme: {
      key: theme.key,
      logo: theme.logo
    },
    title: resolveTitle(theme.title, xuiBaseUrl),
    skipLink: {
      ...DEFAULT_SKIP_LINK
    },
    accountNav: {
      label: DEFAULT_ACCOUNT_NAV_LABEL,
      items: buildAccountItems(context.user.roles)
    },
    primaryNav: {
      leftItems: resolveNavItems(leftItems, xuiBaseUrl),
      rightItems: resolveNavItems(rightItems, xuiBaseUrl)
    }
  };
}

function buildAccountItems(roles: string[]): HeaderModel['accountNav']['items'] {
  if (roles.length === 0) {
    return [];
  }

  return DEFAULT_ACCOUNT_ITEMS.map((item) => ({ ...item }));
}

function resolveTitle(title: HeaderModel['title'], xuiBaseUrl: string): HeaderModel['title'] {
  return {
    ...title,
    href: resolveXuiUrl(xuiBaseUrl, title.href)
  };
}

function resolveNavItems(items: HeaderMenuItem[], xuiBaseUrl: string): HeaderNavItem[] {
  return items.map((item) => {
    if (!item.href) {
      throw new Error(`Header menu item "${item.text}" must have an href.`);
    }

    return {
      text: item.text,
      href: resolveXuiUrl(xuiBaseUrl, item.href)
    };
  });
}

function resolveXuiUrl(xuiBaseUrl: string, href: string): string {
  return resolveUrl(xuiBaseUrl, href);
}
