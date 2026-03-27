import type { FooterModel, FooterNavigation } from './types.js';

export const DEFAULT_FOOTER_NAVIGATION: FooterNavigation = {
  items: [
    { text: 'Accessibility', href: '/accessibility', target: '_blank' },
    { text: 'Terms and conditions', href: '/terms-and-conditions', target: '_blank' },
    { text: 'Cookies', href: '/cookies', target: '_blank' },
    { text: 'Privacy policy', href: '/privacy-policy', target: '_blank' },
    { text: 'Get help', href: '/get-help', target: '_blank' }
  ]
};

export const DEFAULT_OPEN_IN_NEW_WINDOW_TEXT = '(Opens in a new window)';

export const DEFAULT_COPYRIGHT: FooterModel['copyright'] = {
  text: '© Crown copyright',
  href: 'https://www.nationalarchives.gov.uk/information-management/re-using-public-sector-information/uk-government-licensing-framework/crown-copyright/'
};

export const DEFAULT_LANGUAGE_TOGGLE_ACTION = '#toggle-language';
