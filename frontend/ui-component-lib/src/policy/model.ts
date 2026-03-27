import {
  DEFAULT_ACCOUNT_ITEMS,
  DEFAULT_ACCOUNT_NAV_LABEL,
  DEFAULT_ASSETS_PATH,
  DEFAULT_PHASE_BANNER,
  DEFAULT_SKIP_LINK
} from './defaults.js';
import {
  decorateRightNavItemsForSearch,
  filterNavItems,
  splitNavItems,
  withDerivedNavItemIds
} from './navigation.js';
import { resolveMenuConfig, selectMenuItems } from './menu-config.js';
import { resolveTheme } from './theme.js';
import type {
  HeaderContext,
  HeaderModel,
  HeaderNavItem
} from './types.js';

export function buildHeaderModel(context: HeaderContext): HeaderModel {
  const xuiBaseUrl = normaliseXuiBaseUrl(context.xuiBaseUrl);
  const theme = resolveTheme(context);
  const menuConfig = resolveMenuConfig();

  let navItems = selectMenuItems(context.user.roles, menuConfig);
  navItems = filterNavItems(navItems, context.user.roles);
  navItems = withDerivedNavItemIds(navItems);

  const searchAwareNavItems = decorateRightNavItemsForSearch(
    navItems,
    context.user.roles
  );
  const { leftItems, rightItems } = splitNavItems(searchAwareNavItems);

  const search = buildSearchModel(rightItems, xuiBaseUrl);

  return {
    assetsPath: DEFAULT_ASSETS_PATH,
    theme: {
      key: theme.key,
      backgroundColor: theme.backgroundColor,
      logo: theme.logo
    },
    title: resolveTitle(theme.title, xuiBaseUrl),
    skipLink: {
      ...DEFAULT_SKIP_LINK
    },
    accountNav: {
      label: DEFAULT_ACCOUNT_NAV_LABEL,
      items: context.user.roles.length > 0
        ? [...DEFAULT_ACCOUNT_ITEMS].map((item) => ({ ...item }))
        : []
    },
    primaryNav: {
      visible: true,
      leftItems: resolveNavItems(leftItems, xuiBaseUrl),
      rightItems: resolveNavItems(rightItems, xuiBaseUrl)
    },
    phaseBanner: { ...DEFAULT_PHASE_BANNER },
    ...(search ? { search } : {})
  };
}

function buildSearchModel(
  rightItems: HeaderNavItem[],
  xuiBaseUrl: string
): HeaderModel['search'] {
  const searchItem = rightItems.find((item) => item.kind === 'case-reference-search');
  if (!searchItem) {
    return undefined;
  }

  return {
    mode: 'case-reference',
    label: '16-digit case reference:',
    buttonText: 'Find',
    action: resolveXuiUrl(xuiBaseUrl, searchItem.href || '/cases/case-search'),
    name: 'case-reference',
    ...(searchItem.href ? { href: resolveXuiUrl(xuiBaseUrl, searchItem.href) } : {})
  };
}

function resolveTitle(title: HeaderModel['title'], xuiBaseUrl: string): HeaderModel['title'] {
  return {
    ...title,
    href: resolveXuiUrl(xuiBaseUrl, title.href)
  };
}

function resolveNavItems(items: HeaderNavItem[], xuiBaseUrl: string): HeaderNavItem[] {
  return items.map((item) => {
    if (!item.href) {
      return { ...item };
    }

    return {
      ...item,
      href: resolveXuiUrl(xuiBaseUrl, item.href)
    };
  });
}

function normaliseXuiBaseUrl(xuiBaseUrl: string): string {
  if (!xuiBaseUrl || xuiBaseUrl.trim().length === 0) {
    throw new Error('HeaderContext.xuiBaseUrl is required.');
  }

  const parsedBaseUrl = new URL(xuiBaseUrl);

  if (!parsedBaseUrl.pathname.endsWith('/')) {
    parsedBaseUrl.pathname = `${parsedBaseUrl.pathname}/`;
  }

  return parsedBaseUrl.toString();
}

function resolveXuiUrl(xuiBaseUrl: string, href: string): string {
  if (ABSOLUTE_URL_PATTERN.test(href) || href.startsWith('#')) {
    return href;
  }

  return new URL(href.replace(/^\/+/, ''), xuiBaseUrl).toString();
}

const ABSOLUTE_URL_PATTERN = /^[a-zA-Z][a-zA-Z\d+.-]*:\/\//;
