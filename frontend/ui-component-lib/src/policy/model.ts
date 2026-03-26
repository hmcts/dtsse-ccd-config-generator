import {
  DEFAULT_ACCOUNT_ITEMS,
  DEFAULT_ACCOUNT_NAV_LABEL,
  DEFAULT_MENU_FLAGS,
  DEFAULT_PHASE_BANNER,
  DEFAULT_SKIP_LINK
} from './defaults.js';
import {
  decorateRightNavItemsForSearch,
  filterNavItems,
  setActiveNavItems,
  shouldShowPrimaryNav,
  splitNavItems,
  withDerivedNavItemIds
} from './navigation.js';
import { resolveMenuConfig, selectMenuItems } from './menu-config.js';
import { resolveTheme } from './theme.js';
import type {
  HeaderAccountItem,
  HeaderContext,
  HeaderModel,
  HeaderNavItem,
  HeaderPhaseBannerModel
} from './types.js';

export function buildHeaderModel(context: HeaderContext): HeaderModel {
  const theme = resolveTheme(context);
  const mergedFeatures = {
    ...DEFAULT_MENU_FLAGS,
    ...context.features
  };

  const menuConfig = resolveMenuConfig(
    context.environment ?? 'prod',
    context.config?.menuConfig
  );

  let navItems = selectMenuItems(context.user.roles, menuConfig);
  navItems = filterNavItems(navItems, context.user.roles, mergedFeatures);
  navItems = setActiveNavItems(navItems, context.route.path);
  navItems = withDerivedNavItemIds(navItems);

  if (shouldHideBookingNav(context)) {
    navItems = [];
  }

  const searchAwareNavItems = decorateRightNavItemsForSearch(
    navItems,
    context.user.roles,
    mergedFeatures
  );
  const { leftItems, rightItems } = splitNavItems(searchAwareNavItems);

  const search = buildSearchModel(rightItems);
  const phaseBanner = resolvePhaseBanner(context);

  return {
    theme: {
      key: theme.key,
      backgroundColor: theme.backgroundColor,
      logo: theme.logo
    },
    title: theme.title,
    skipLink: {
      ...DEFAULT_SKIP_LINK
    },
    accountNav: {
      label: DEFAULT_ACCOUNT_NAV_LABEL,
      items: resolveAccountItems(context)
    },
    primaryNav: {
      visible: shouldShowPrimaryNav(context.route.path),
      leftItems,
      rightItems
    },
    ...(phaseBanner ? { phaseBanner } : {}),
    ...(search ? { search } : {})
  };
}

function resolveAccountItems(context: HeaderContext): HeaderAccountItem[] {
  if (!context.user.isAuthenticated || context.user.roles.length === 0) {
    return [];
  }

  const accountItems = context.config?.accountItems ?? [...DEFAULT_ACCOUNT_ITEMS];
  return accountItems.map((item) => ({ ...item }));
}

function shouldHideBookingNav(context: HeaderContext): boolean {
  return (
    context.route.path.includes('booking') &&
    context.user.roleCategory === 'JUDICIAL' &&
    context.user.bookable === true
  );
}

function buildSearchModel(rightItems: HeaderNavItem[]): HeaderModel['search'] {
  const searchItem = rightItems.find((item) => item.kind === 'case-reference-search');
  if (!searchItem) {
    return undefined;
  }

  return {
    mode: 'case-reference',
    label: '16-digit case reference:',
    buttonText: 'Find',
    action: searchItem.href || '/cases/case-search',
    name: 'case-reference',
    ...(searchItem.href ? { href: searchItem.href } : {})
  };
}

function resolvePhaseBanner(context: HeaderContext): HeaderPhaseBannerModel | undefined {
  if (context.config?.phaseBanner === null) {
    return undefined;
  }

  return {
    ...DEFAULT_PHASE_BANNER,
    ...(context.config?.phaseBanner ?? {})
  };
}
