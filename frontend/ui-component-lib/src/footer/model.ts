import {
  DEFAULT_COPYRIGHT,
  DEFAULT_FOOTER_NAVIGATION,
  DEFAULT_OPEN_IN_NEW_WINDOW_TEXT
} from './defaults.js';
import type { FooterContext, FooterModel } from './types.js';
import { normaliseRequiredBaseUrl, resolveUrl } from '../urls.js';

export function buildFooterModel(context: FooterContext = {}): FooterModel {
  const xuiBaseUrl = context.xuiBaseUrl === undefined
    ? undefined
    : normaliseRequiredBaseUrl(
        context.xuiBaseUrl,
        'FooterContext.xuiBaseUrl'
      );

  return {
    navigation: {
      items: DEFAULT_FOOTER_NAVIGATION.items.map((item) => ({
        ...item,
        href: xuiBaseUrl ? resolveUrl(xuiBaseUrl, item.href) : item.href
      }))
    },
    openInNewWindowText: DEFAULT_OPEN_IN_NEW_WINDOW_TEXT,
    copyright: {
      ...DEFAULT_COPYRIGHT
    }
  };
}
