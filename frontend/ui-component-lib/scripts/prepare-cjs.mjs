import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const cjsDir = path.resolve('dist', 'cjs');

await mkdir(cjsDir, { recursive: true });
await writeFile(
  path.join(cjsDir, 'package.json'),
  JSON.stringify({ type: 'commonjs' }, null, 2) + '\n',
  'utf8'
);
