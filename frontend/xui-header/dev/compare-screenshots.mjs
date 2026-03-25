import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { PNG } from 'pngjs';
import pixelmatch from 'pixelmatch';

const [referencePath, candidatePath, diffPathArg] = process.argv.slice(2);

if (!referencePath || !candidatePath) {
  console.error(
    'Usage: npm run compare:screenshots -- <reference.png> <candidate.png> [diff.png]'
  );
  process.exit(1);
}

const diffPath = diffPathArg ?? path.join(process.cwd(), 'build', 'parity-diff.png');

const reference = PNG.sync.read(fs.readFileSync(referencePath));
const candidate = PNG.sync.read(fs.readFileSync(candidatePath));

if (reference.width !== candidate.width) {
  console.error(
    `Image widths differ: reference=${reference.width}, candidate=${candidate.width}`
  );
  process.exit(2);
}

const comparisonHeight = Math.min(reference.height, candidate.height);
const croppedReference = cropTop(reference, comparisonHeight);
const croppedCandidate = cropTop(candidate, comparisonHeight);

const diff = new PNG({ width: reference.width, height: comparisonHeight });
const mismatchedPixels = pixelmatch(
  croppedReference.data,
  croppedCandidate.data,
  diff.data,
  reference.width,
  comparisonHeight,
  {
    threshold: 0.1
  }
);

fs.mkdirSync(path.dirname(diffPath), { recursive: true });
fs.writeFileSync(diffPath, PNG.sync.write(diff));

const totalPixels = reference.width * reference.height;
const comparedPixels = reference.width * comparisonHeight;
const mismatchPercent = ((mismatchedPixels / comparedPixels) * 100).toFixed(2);

console.log(
  JSON.stringify(
    {
      referencePath,
      candidatePath,
      diffPath,
      width: reference.width,
      referenceHeight: reference.height,
      candidateHeight: candidate.height,
      comparedHeight: comparisonHeight,
      mismatchedPixels,
      totalPixels: comparedPixels,
      mismatchPercent
    },
    null,
    2
  )
);

function cropTop(image, height) {
  if (image.height === height) {
    return image;
  }

  const cropped = new PNG({ width: image.width, height });
  PNG.bitblt(image, cropped, 0, 0, image.width, height, 0, 0);
  return cropped;
}
