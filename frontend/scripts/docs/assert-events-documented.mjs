#!/usr/bin/env node
/**
 * Fails if the taxonomy and its documentation disagree, in either direction, about:
 *
 *   1. which events exist, client AND server,
 *   2. which properties each event carries,
 *   3. the members of a closed vocabulary (Surface, ErrorCategory, SettingKey, ...).
 *
 * The server events are listed in the client taxonomy as SERVER_EVENT_NAMES so the client can
 * never define a competing version of the same fact. The backend holds the same list in
 * ServerAnalyticsEvent.java, and a Java test pins those two together; this script is what pins
 * them to the document.
 *
 * A taxonomy document rots the moment someone adds an event in a hurry, and a stale one is worse
 * than none: it is read as authoritative. Checking the event names alone was not enough. Adding a
 * property or a vocabulary member is the commonest change by far, and both used to slip through.
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

const failures = [];

// ---------------------------------------------------------------------------------------------
// 1. Event names.
// ---------------------------------------------------------------------------------------------

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

// Every event gets its own table row, first cell. Matching on that rather than on any backticked
// word keeps property names, step slugs and error categories (which also appear in backticks) from
// being mistaken for events in either direction.
const rows = new Map(
  [...docSource.matchAll(/^\|\s*`([a-z0-9_]+)`\s*\|([^|]*)\|/gm)].map((m) => [m[1], m[2]]),
);

for (const name of eventNames) {
  if (!rows.has(name)) {
    failures.push(`${name} is defined in the taxonomy but has no row in FREDdocs/analytics-events.md`);
  }
}

// ---------------------------------------------------------------------------------------------
// 1b. Server event names. Same rule, different list: these are emitted by the backend through the
// transactional outbox, and a reader of the document must be able to find every one of them.
// ---------------------------------------------------------------------------------------------

const serverListMatch = taxonomySource.match(
  /export const SERVER_EVENT_NAMES = \[([\s\S]*?)\] as const;/,
);
if (!serverListMatch) {
  console.error('FAIL: could not find SERVER_EVENT_NAMES in', path.relative(repoRoot, TAXONOMY));
  process.exit(1);
}

const serverEventNames = [...serverListMatch[1].matchAll(/'([a-z0-9_]+)'/g)].map((m) => m[1]);
if (serverEventNames.length === 0) {
  console.error('FAIL: SERVER_EVENT_NAMES is empty');
  process.exit(1);
}

for (const name of serverEventNames) {
  if (!rows.has(name)) {
    failures.push(
      `${name} is a server event but has no row in FREDdocs/analytics-events.md`,
    );
  }
  if (eventNames.includes(name)) {
    failures.push(`${name} is on BOTH the client and the server list; one fact, one emitter`);
  }
}

// A documented row that is on neither list is a lie in the other direction. Checked here rather
// than in the client loop above, which cannot see the server names.
const allEventNames = new Set([...eventNames, ...serverEventNames]);
for (const documented of rows.keys()) {
  if (!allEventNames.has(documented)) {
    failures.push(
      `${documented} has a row in FREDdocs/analytics-events.md but is in neither ` +
        'CLIENT_EVENT_NAMES nor SERVER_EVENT_NAMES (renamed or removed?)',
    );
  }
}

// ---------------------------------------------------------------------------------------------
// 2. Properties, per event.
// ---------------------------------------------------------------------------------------------

/**
 * The property names in an event's row. Some rows gloss a property's values in parentheses, e.g.
 * "`source` (`orb` / `history`)", so the parenthesised parts are dropped before reading the
 * backticked words; otherwise the values would be mistaken for properties.
 */
function documentedPropertiesFor(eventName) {
  const cell = rows.get(eventName);
  if (cell === undefined) {
    return null;
  }
  const withoutValueGlosses = cell.replace(/\([^)]*\)/g, '');
  return [...withoutValueGlosses.matchAll(/`([a-z0-9_$]+)`/g)].map((m) => m[1]);
}

/**
 * The property names of one member of the AnalyticsEvent union. The union holds both one-line
 * members and multi-line ones, so the props block is found by balancing braces rather than by a
 * shape-specific pattern, and comments are stripped before the names are read.
 */
function taxonomyPropertiesFor(eventName) {
  const anchor = taxonomySource.indexOf(`name: '${eventName}';`);
  if (anchor === -1) {
    return null;
  }
  const open = taxonomySource.indexOf('{', taxonomySource.indexOf('props:', anchor));
  if (open === -1) {
    return null;
  }
  let depth = 0;
  let close = -1;
  for (let i = open; i < taxonomySource.length; i++) {
    if (taxonomySource[i] === '{') depth++;
    else if (taxonomySource[i] === '}' && --depth === 0) {
      close = i;
      break;
    }
  }
  if (close === -1) {
    return null;
  }
  return taxonomySource
    .slice(open + 1, close)
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
    .split(/[;\n]/)
    // The `?` of an optional property sits between the name and the colon. Leaving it out of this
    // pattern made optional properties invisible, so one could be added and never documented.
    .map((line) => line.trim().match(/^([a-z0-9_$]+)\??\s*:/))
    .filter(Boolean)
    .map((m) => m[1]);
}

// Only the CLIENT events have a typed props block to compare against. A server event's properties
// are defined in Java, pinned by the backend's own tests, and documented here; the names check
// above is what keeps this document honest about them.
for (const name of eventNames) {
  const documented = documentedPropertiesFor(name);
  const defined = taxonomyPropertiesFor(name);
  if (documented === null) {
    continue; // already reported as a missing row
  }
  if (defined === null) {
    failures.push(`could not read the props of ${name} from the taxonomy`);
    continue;
  }
  for (const prop of defined) {
    if (!documented.includes(prop)) {
      failures.push(`${name} carries \`${prop}\`, which its row in the doc does not list`);
    }
  }
  for (const prop of documented) {
    if (!defined.includes(prop)) {
      failures.push(`the ${name} row lists \`${prop}\`, which the taxonomy does not define`);
    }
  }
}

// ---------------------------------------------------------------------------------------------
// 3. Closed vocabularies. A new member nobody documents is how a document starts lying.
// ---------------------------------------------------------------------------------------------

// Derived from the taxonomy rather than listed here. A hand-kept list is a list someone forgets:
// every closed string-literal union in the file is a vocabulary whose members reach the vendor, so
// adding one to the taxonomy now opts it into this check automatically.
const VOCABULARIES = [...taxonomySource.matchAll(/export type (\w+) =([^;]*);/g)]
  .filter(([, , body]) => {
    const withoutMembers = body
      .replace(/'[a-z0-9_]+'/g, '')
      .replace(/\/\/.*$/gm, '')
      // `| null` is how an optional vocabulary says "none", e.g. Tier. It is not a member to
      // document, but it must not disqualify the union from being checked.
      .replace(/\bnull\b/g, '');
    // Only unions made purely of string literals: anything else (a composed or generic type) has
    // no members of its own to document. Members must exist, or there is nothing to check.
    return /'[a-z0-9_]+'/.test(body) && /^[\s|]*$/.test(withoutMembers);
  })
  .map(([, name]) => name);

if (VOCABULARIES.length === 0) {
  failures.push('found no closed vocabularies in the taxonomy, which cannot be right');
}

// A vocabulary is documented as ONE list, in a table row or a sentence. Checking each member
// against every backticked word in the whole document was not good enough: values are shared
// between vocabularies ('goals' is both a Surface and a GatedFeature, 'conservative' is both a
// ReturnPreset and a MarketScenario), so deleting a member from its list still passed because the
// word survived somewhere else. Each vocabulary is therefore pinned to the block that documents
// it: the paragraph or table that mentions the most of its members has to mention all of them.
const blocks = docSource.split(/\n\s*\n/);

for (const vocabulary of VOCABULARIES) {
  const declaration = taxonomySource.match(new RegExp(`export type ${vocabulary} =([\\s\\S]*?);`));
  if (!declaration) {
    failures.push(`could not find the ${vocabulary} vocabulary in the taxonomy`);
    continue;
  }
  const members = [...declaration[1].matchAll(/'([a-z0-9_]+)'/g)].map((m) => m[1]);
  if (members.length === 0) {
    continue;
  }

  const mentions = (block) => members.filter((m) => block.includes(`\`${m}\``));
  const best = blocks.reduce(
    (winner, block) => (mentions(block).length > mentions(winner).length ? block : winner),
    '',
  );
  const documented = mentions(best);

  if (documented.length === 0) {
    failures.push(
      `${vocabulary} is documented nowhere in FREDdocs/analytics-events.md ` +
        `(expected a list containing ${members.map((m) => '`' + m + '`').join(', ')})`,
    );
    continue;
  }
  for (const member of members) {
    if (!documented.includes(member)) {
      failures.push(
        `${vocabulary} has the value \`${member}\`, which is missing from the list that ` +
          `documents ${vocabulary} in FREDdocs/analytics-events.md`,
      );
    }
  }
}

if (failures.length > 0) {
  console.error('FAIL: taxonomy and documentation disagree\n');
  for (const failure of failures) {
    console.error(`  - ${failure}`);
  }
  process.exit(1);
}

console.log(
  `PASS: ${eventNames.length} client events and their properties, ${serverEventNames.length} ` +
    `server events, and ${VOCABULARIES.length} vocabularies all match ` +
    'FREDdocs/analytics-events.md',
);
