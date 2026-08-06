import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import nunjucks from 'nunjucks';
import * as packageApi from '../dist/index.js';

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const nunjucksEnvironment = nunjucks.configure(
  path.join(packageRoot, 'src', 'nunjucks'),
  { autoescape: true }
);
const { buildFooterModel, buildHeaderModel } = packageApi;
const require = createRequire(import.meta.url);

function buildHeader(roles, overrides = {}) {
  return buildHeaderModel({
    xuiBaseUrl: 'https://manage-case.example/xui/',
    environment: 'prod',
    user: { roles },
    ...overrides
  });
}

function navTexts(model) {
  return [
    ...model.primaryNav.leftItems,
    ...model.primaryNav.rightItems
  ].map((item) => item.text);
}

function renderHeader(model) {
  return nunjucksEnvironment.renderString(
    '{% from "xui-header/macro.njk" import hmctsXuiHeader %}{{ hmctsXuiHeader(model) }}',
    { model }
  );
}

function renderFooter(model) {
  return nunjucksEnvironment.renderString(
    '{% from "footer/macro.njk" import hmctsUiFooter %}{{ hmctsUiFooter(model) }}',
    { model }
  );
}

test('exports only the two model builders at runtime', () => {
  assert.deepEqual(
    Object.keys(packageApi).sort(),
    ['buildFooterModel', 'buildHeaderModel']
  );
});

test('provides the same reduced CommonJS API', () => {
  const commonJsApi = require('../dist/cjs/index.js');

  assert.deepEqual(
    Object.keys(commonJsApi).sort(),
    ['buildFooterModel', 'buildHeaderModel']
  );
});

test('centrally selects the header theme from roles', () => {
  assert.equal(buildHeader(['caseworker-civil']).theme.key, 'default');
  assert.equal(buildHeader(['caseworker-divorce-judge']).theme.key, 'judicial');
  assert.equal(buildHeader(['pui-case-manager']).theme.key, 'myhmcts');
});

test('keeps non-production navigation differences out of production', () => {
  const roles = ['caseworker-sscs-clerk'];
  const production = buildHeader(roles);
  const aat = buildHeader(roles, { environment: 'aat' });

  assert.equal(navTexts(production).includes('My work'), false);
  assert.equal(navTexts(production).includes('Search'), false);
  assert.equal(navTexts(aat).includes('My work'), true);
  assert.equal(navTexts(aat).includes('Search'), true);

  const probateProduction = buildHeader(['caseworker-probate']);
  const probateAat = buildHeader(
    ['caseworker-probate'],
    { environment: 'aat' }
  );
  assert.equal(navTexts(probateProduction).includes('Search'), false);
  assert.equal(navTexts(probateAat).includes('Search'), true);
});

test('uses legacy AAT navigation when the environment is omitted', () => {
  const roles = ['caseworker-sscs-clerk'];

  assert.deepEqual(
    navTexts(buildHeader(roles, { environment: undefined })),
    navTexts(buildHeader(roles, { environment: 'aat' }))
  );
});

test('rejects an unknown environment', () => {
  assert.throws(
    () => buildHeader(['caseworker-civil'], { environment: 'unknown' }),
    /environment must be/
  );
});

test('includes current MyHMCTS notice-of-change roles', () => {
  const model = buildHeader([
    'pui-case-manager',
    'caseworker-pcs-solicitor'
  ]);

  assert.equal(navTexts(model).includes('Notice of change'), true);
});

test('resolves XUI links and host-owned paths without losing base paths', () => {
  const model = buildHeader(['caseworker-civil']);

  assert.equal(model.assetsPath, '/assets/ui-component-lib');
  assert.equal(model.title.href, 'https://manage-case.example/xui/');
  assert.equal(
    model.primaryNav.leftItems[0]?.href,
    'https://manage-case.example/xui/work/my-work/list'
  );
  assert.equal(model.accountNav.items[0]?.href, '/logout');
});

test('uses the fixed logout path only for signed-in users', () => {
  assert.equal(
    buildHeader(['caseworker-civil']).accountNav.items[0]?.href,
    '/logout'
  );

  const signedOut = buildHeader([]);
  assert.deepEqual(signedOut.accountNav.items, []);
  assert.deepEqual(signedOut.primaryNav.leftItems, []);
  assert.deepEqual(signedOut.primaryNav.rightItems, []);
});

test('resolves footer navigation against XUI', () => {
  const model = buildFooterModel({
    xuiBaseUrl: 'https://manage-case.example/xui/'
  });

  assert.equal(
    model.navigation.items[0]?.href,
    'https://manage-case.example/xui/accessibility'
  );
});

test('supports the legacy minimal builders without host configuration', () => {
  const header = buildHeaderModel({
    xuiBaseUrl: 'https://manage-case.example',
    user: { roles: ['caseworker-sscs-clerk'] }
  });
  const footer = buildFooterModel();

  assert.equal(header.assetsPath, '/assets/ui-component-lib');
  assert.equal(header.accountNav.items[0]?.href, '/logout');
  assert.equal(navTexts(header).includes('My work'), true);
  assert.equal(footer.navigation.items[0]?.href, '/accessibility');
});

test('rejects non-HTTP XUI base URLs', () => {
  assert.throws(
    () => buildHeader(['caseworker-civil'], { xuiBaseUrl: 'file:///tmp/xui' }),
    /must use http or https/
  );
});

test('renders a link-only header with one primary navigation landmark', () => {
  const html = renderHeader(buildHeader(['pui-case-manager']));

  assert.match(html, /shadowrootmode="open"/);
  assert.match(html, /href="\/logout"/);
  assert.doesNotMatch(html, /href="#"/);
  assert.doesNotMatch(html, /<form|<button|phase-banner|case-reference-search/);
  assert.equal(
    (html.match(/<nav class="hmcts-primary-navigation__nav"/g) ?? []).length,
    1
  );
});

test('renders no client-side language action in the footer', () => {
  const html = renderFooter(buildFooterModel({
    xuiBaseUrl: 'https://manage-case.example'
  }));

  assert.doesNotMatch(html, /<button|data-footer-action|toggle-language/);
  assert.match(html, /href="https:\/\/manage-case\.example\/accessibility"/);
  assert.match(html, /target="_blank" rel="noopener noreferrer"/);
});

test('keeps header CSS in the shadow stylesheet only', async () => {
  const [globalCss, shadowCss] = await Promise.all([
    readFile(path.join(packageRoot, 'src', 'styles', 'ui-component-lib.css'), 'utf8'),
    readFile(path.join(packageRoot, 'src', 'styles', 'xui-header-shadow.css'), 'utf8')
  ]);

  assert.doesNotMatch(globalCss, /\.xui-header/);
  assert.doesNotMatch(shadowCss, /phase-banner|search-form|action-link/);
});
