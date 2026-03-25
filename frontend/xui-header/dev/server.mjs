import express from 'express';
import nunjucks from 'nunjucks';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createPreviewScenarios } from './fixtures.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const packageRoot = path.resolve(__dirname, '..');
const distRoot = path.join(packageRoot, 'dist');

const { buildHeaderModel } = await import(path.join(distRoot, 'index.js'));

const app = express();
const port = Number(process.env.PORT || 3100);

const nunjucksEnv = nunjucks.configure(
  [
    path.join(packageRoot, 'dev', 'templates'),
    path.join(packageRoot, 'src', 'nunjucks')
  ],
  {
    autoescape: true,
    express: app
  }
);

nunjucksEnv.addFilter('dump', (value) => JSON.stringify(value, null, 2));

app.use('/styles', express.static(path.join(packageRoot, 'src', 'styles')));

app.get('/', (_req, res) => {
  const scenarios = createPreviewScenarios(buildHeaderModel);
  res.render('preview.njk', { scenarios });
});

app.get('/scenario/:id', (req, res) => {
  const scenarios = createPreviewScenarios(buildHeaderModel);
  const scenario = scenarios.find((candidate) => candidate.id === req.params.id);

  if (!scenario) {
    res.status(404).send(`Unknown scenario: ${req.params.id}`);
    return;
  }

  res.render('scenario.njk', { scenario });
});

app.listen(port, () => {
  console.log(`xui-header preview available at http://localhost:${port}`);
});
