#!/usr/bin/env node
/**
 * Fails if an event exists in the taxonomy but not in its documentation, or the other way round.
 *
 * A taxonomy document rots the moment someone adds an event in a hurry, and a stale one is worse
 * than none: it is read as authoritative. This keeps the two in step mechanically.
 *
 * Run: npm run test:events-documented
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const repoRoot = path.resolve(frontendRoot, '..');

const TAXONOMY = path.join(frontendRoot, 'src/app/services/analytics/analytics-events.ts');
const DOC = path.join(repoRoot, 'FREDdocs/analytics-events.md');

const taxonomySource = readFileSync(TAXONOMY, 'utf8');
const docSource = readFileSync(DOC, 'utf8');

// CLIENT_EVENT_NAMES is the runtime mirror of the union, and the compiler already proves the two
// agree, so reading it is enough to know every event.
const listMatch = taxonomySource.match(/export const CLIENT_EVENT_NAMES = \[([\s\S]*?)\] as const;/);
if (!listMatch) {
  console.error('FAIL: could not find CLIENT_EVENT_NAMES in', path.relative(repoRoot, TAXONOMY));
  process.exit(1);
}

const eventNames = [...listMatch[1].matchAll(/'([a-z0-9_]+)'/g)].map((m) => m[1]);
if (eventNames.length === 0) {
  console.error('FAIL: CLIENT_EVENT_NAMES is empty');
  process.exit(1);
}

const failures = [];

// Every event gets its own table row, first cell. Matching on that rather than on any backticked
// word keeps property names, step slugs and error categories (which also appear in backticks) from
// being mistaken for events in either direction.
const documentedInTable = new Set([...docSource.matchAll(/^\|\s*`([a-z0-9_]+)`\s*\|/gm)].map((m) => m[1]));

for (const name of eventNames) {
  if (!documentedInTable.has(name)) {
    failures.push(
      `${name} is defined in the taxonomy but has no row in FREDdocs/analytics-events.md`,
    );
  }
}

// And the reverse: a documented event that no longer exists is a lie about what the app sends.
for (const documented of documentedInTable) {
  if (!eventNames.includes(documented)) {
    failures.push(
      `${documented} has a row in FREDdocs/analytics-events.md but is not in CLIENT_EVENT_NAMES (renamed or removed?)`,
    );
  }
}

if (failures.length > 0) {
  console.error('FAIL: taxonomy and documentation disagree\n');
  for (const failure of failures) {
    console.error(`  - ${failure}`);
  }
  process.exit(1);
}

console.log(`PASS: all ${eventNames.length} client events are documented in FREDdocs/analytics-events.md`);
