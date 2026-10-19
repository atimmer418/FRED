#!/usr/bin/env node
/**
 * Proves the posthog-js import restriction actually fires, and that the adapter still scrubs
 * through a hook the SDK supports.
 *
 * A lint rule nobody tests is a comment. This runs ESLint twice: once on a fixture that imports
 * posthog-js from outside the adapter (must be reported), once on the real adapter (must be
 * clean, or the one legitimate importer would be blocked and the rule would get switched off).
 *
 * It then reads the adapter itself. The scrub is the last line of defence on everything the SDK
 * adds on its own, and it is wired through one init option, so a rename or a silent removal there
 * would switch it off with nothing else failing. `sanitize_properties` is the deprecated option it
 * used to use: posthog-js logs an error on every capture and will drop it, so it must not come
 * back.
 *
 * Run: npm run test:lint-rule
 */
import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const execFileAsync = promisify(execFile);

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const VIOLATION = 'scripts/lint/fixtures/posthog-import-violation.ts';
const ALLOWED = 'src/app/services/analytics/posthog.adapter.ts';
const RULE = 'no-restricted-imports';

async function lint(relativePath) {
  try {
    const { stdout } = await execFileAsync(
      'npx',
      ['eslint', '--no-ignore', '--format', 'json', relativePath],
      {
        cwd: frontendRoot,
        // The project still uses .eslintrc.json; ESLint 9 would look for a flat config otherwise.
        env: { ...process.env, ESLINT_USE_FLAT_CONFIG: 'false' },
        maxBuffer: 10 * 1024 * 1024,
      },
    );
    return JSON.parse(stdout);
  } catch (error) {
    // ESLint exits non-zero when it reports problems, which is the expected case for the fixture.
    if (error.stdout) {
      return JSON.parse(error.stdout);
    }
    throw error;
  }
}

function messagesForRule(results) {
  return results.flatMap((result) => result.messages.filter((message) => message.ruleId === RULE));
}

const failures = [];

const violation = messagesForRule(await lint(VIOLATION));
if (violation.length === 0) {
  failures.push(
    `${VIOLATION} imports posthog-js and should have been reported by ${RULE}, but lint was clean. ` +
      'The restriction is not in effect.',
  );
} else if (!violation[0].message.includes('posthog.adapter.ts')) {
  failures.push(
    `${RULE} fired but its message does not say where posthog-js may be imported, so the next ` +
      `developer will not know what to do. Got: ${violation[0].message}`,
  );
}

const allowed = messagesForRule(await lint(ALLOWED));
if (allowed.length > 0) {
  failures.push(
    `${ALLOWED} is the one file allowed to import posthog-js, but ${RULE} reported it: ` +
      `${allowed[0].message}`,
  );
}

// The scrub hook, read from the adapter source. Comments are stripped first: the file explains
// in prose which option it moved away from, and that sentence is not a use of it.
const adapterSource = (await readFile(path.join(frontendRoot, ALLOWED), 'utf8'))
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/\/\/.*$/gm, '');

if (adapterSource.includes('sanitize_properties')) {
  failures.push(
    `${ALLOWED} uses sanitize_properties, which posthog-js has deprecated and will remove. ` +
      'Use before_send, which buildBeforeSend wraps.',
  );
}
if (!/before_send:\s*buildBeforeSend\(config\.sanitize\)/.test(adapterSource)) {
  failures.push(
    `${ALLOWED} does not pass the service's sanitizer to before_send. Without it the SDK sends ` +
      'the properties it adds itself unscrubbed, including the URL ones that carry route ids.',
  );
}

if (failures.length > 0) {
  console.error('FAIL: posthog-js adapter rules\n');
  for (const failure of failures) {
    console.error(`  - ${failure}`);
  }
  process.exit(1);
}

console.log(
  `PASS: ${RULE} blocks posthog-js outside the adapter ` +
    `(${violation.length} message(s) on the fixture, 0 on the adapter), ` +
    'and the adapter scrubs through before_send',
);
