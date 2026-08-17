const ABSOLUTE_URL_PATTERN = /^[a-zA-Z][a-zA-Z\d+.-]*:\/\//;

export function normaliseRequiredBaseUrl(value: string, fieldName: string): string {
  if (!value || value.trim().length === 0) {
    throw new Error(`${fieldName} is required.`);
  }

  const parsedBaseUrl = new URL(value);
  if (parsedBaseUrl.protocol !== 'http:' && parsedBaseUrl.protocol !== 'https:') {
    throw new Error(`${fieldName} must use http or https.`);
  }

  if (!parsedBaseUrl.pathname.endsWith('/')) {
    parsedBaseUrl.pathname = `${parsedBaseUrl.pathname}/`;
  }

  return parsedBaseUrl.toString();
}

export function resolveUrl(baseUrl: string, href: string): string {
  if (ABSOLUTE_URL_PATTERN.test(href) || href.startsWith('#')) {
    return href;
  }

  return new URL(href.replace(/^\/+/, ''), baseUrl).toString();
}
