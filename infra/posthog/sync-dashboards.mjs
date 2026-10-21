#!/usr/bin/env node
/**
 * Creates or updates the FRED PostHog dashboards from the JSON in ./dashboards.
 *
 * Dashboards built by hand drift, and nobody can say what changed or why. These are defined in
 * version control and applied by this script, so the review history of a metric is the git history
 * of its file.
 *
 * IDEMPOTENT. Run it as often as you like. Dashboards and tiles are matched BY NAME:
 *   - a dashboard whose name is not there yet is created
 *   - one that is there is updated in place, keeping its id, so shared links keep working
 *   - a tile whose name is already on the dashboard is updated, not duplicated
 *   - a tile that is no longer in the JSON is DELETED from the dashboard, so removing a tile from
 *     version control actually removes it
 *
 * Renaming a dashboard or a tile in the JSON therefore creates a new one and orphans the old. That
 * is deliberate: silently renaming something people have bookmarked is worse than leaving a stale
 * copy that a human can delete.
 *
 * Usage:
 *   POSTHOG_PERSONAL_API_KEY=phx_... node infra/posthog/sync-dashboards.mjs
 *   ... --dry-run     show what would change, touch nothing
 *   ... --host=https://eu.posthog.com
 *   ... --project=12345
 *
 * See README.md for the exact API-key scopes this needs.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const DASHBOARD_DIR = path.join(here, 'dashboards');

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const flag = (name, fallback) => {
  const found = args.find((a) => a.startsWith(`--${name}=`));
  return found ? found.slice(name.length + 3) : fallback;
};

const HOST = (flag('host', process.env.POSTHOG_HOST || 'https://us.posthog.com')).replace(/\/$/, '');
const API_KEY = process.env.POSTHOG_PERSONAL_API_KEY;
let PROJECT_ID = flag('project', process.env.POSTHOG_PROJECT_ID);

if (!API_KEY) {
  console.error(
    'POSTHOG_PERSONAL_API_KEY is not set.\n'
      + 'Create a personal API key in PostHog under Settings -> Personal API keys, with the\n'
      + 'scopes listed in infra/posthog/README.md, and export it before running this.',
  );
  process.exit(1);
}

async function api(method, pathname, body) {
  const response = await fetch(`${HOST}${pathname}`, {
    method,
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text();
    // 403 here is nearly always a missing scope rather than a wrong key, so say so.
    const hint = response.status === 403
      ? '\nThis is usually a missing scope on the personal API key. See infra/posthog/README.md.'
      : '';
    throw new Error(`${method} ${pathname} -> ${response.status}\n${text}${hint}`);
  }
  return response.status === 204 ? null : response.json();
}

/** Walks a paginated PostHog list endpoint to the end. */
async function listAll(pathname) {
  const results = [];
  let next = `${pathname}${pathname.includes('?') ? '&' : '?'}limit=100`;
  while (next) {
    const page = await api('GET', next.startsWith('http') ? new URL(next).pathname + new URL(next).search : next);
    results.push(...(page.results || []));
    next = page.next;
  }
  return results;
}

async function resolveProjectId() {
  if (PROJECT_ID) {
    return PROJECT_ID;
  }
  const projects = await listAll('/api/projects/');
  if (projects.length === 0) {
    throw new Error('the API key can see no projects');
  }
  if (projects.length > 1) {
    const names = projects.map((p) => `${p.id} (${p.name})`).join(', ');
    throw new Error(
      `the API key can see ${projects.length} projects, so the target is ambiguous: ${names}\n`
        + 'Pass --project=<id> or set POSTHOG_PROJECT_ID.',
    );
  }
  return projects[0].id;
}

function loadDefinitions() {
  return readdirSync(DASHBOARD_DIR)
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((file) => ({ file, ...JSON.parse(readFileSync(path.join(DASHBOARD_DIR, file), 'utf8')) }));
}

async function syncDashboard(projectId, definition, existingDashboards) {
  const base = `/api/projects/${projectId}`;
  const existing = existingDashboards.find((d) => d.name === definition.name);

  let dashboard = existing;
  if (!dashboard) {
    console.log(`  + creating dashboard "${definition.name}"`);
    if (dryRun) {
      return { created: 1, updated: 0, tiles: definition.tiles.length, removed: 0 };
    }
    dashboard = await api('POST', `${base}/dashboards/`, {
      name: definition.name,
      description: definition.description,
    });
  } else if (dashboard.description !== definition.description) {
    console.log(`  ~ updating description of "${definition.name}"`);
    if (!dryRun) {
      await api('PATCH', `${base}/dashboards/${dashboard.id}/`, {
        description: definition.description,
      });
    }
  }

  // Tiles are insights that belong to this dashboard. Match by name.
  const onDashboard = dryRun && !existing
    ? []
    : await listAll(`${base}/insights/?dashboards=${dashboard.id}`);

  let created = 0;
  let updated = 0;
  for (const tile of definition.tiles) {
    const payload = {
      name: tile.name,
      description: tile.description,
      query: tile.query,
      dashboards: [dashboard.id],
    };
    const found = onDashboard.find((i) => i.name === tile.name);
    if (found) {
      console.log(`    ~ tile "${tile.name}"`);
      updated += 1;
      if (!dryRun) {
        await api('PATCH', `${base}/insights/${found.id}/`, payload);
      }
    } else {
      console.log(`    + tile "${tile.name}"`);
      created += 1;
      if (!dryRun) {
        await api('POST', `${base}/insights/`, payload);
      }
    }
  }

  // Anything on the dashboard that version control no longer describes.
  const wanted = new Set(definition.tiles.map((t) => t.name));
  const orphans = onDashboard.filter((i) => !wanted.has(i.name));
  for (const orphan of orphans) {
    console.log(`    - removing tile "${orphan.name}" (no longer in the JSON)`);
    if (!dryRun) {
      await api('PATCH', `${base}/insights/${orphan.id}/`, { dashboards: [] });
    }
  }

  return {
    created: existing ? 0 : 1,
    updated: existing ? 1 : 0,
    tiles: created + updated,
    removed: orphans.length,
  };
}

async function main() {
  const projectId = await resolveProjectId();
  console.log(`${dryRun ? '[dry run] ' : ''}syncing dashboards to project ${projectId} at ${HOST}\n`);

  const definitions = loadDefinitions();
  if (definitions.length === 0) {
    console.error(`no dashboard definitions found in ${DASHBOARD_DIR}`);
    process.exit(1);
  }

  const existingDashboards = await listAll(`/api/projects/${projectId}/dashboards/`);
  const totals = { created: 0, updated: 0, tiles: 0, removed: 0 };

  for (const definition of definitions) {
    console.log(`${definition.file}`);
    const result = await syncDashboard(projectId, definition, existingDashboards);
    totals.created += result.created;
    totals.updated += result.updated;
    totals.tiles += result.tiles;
    totals.removed += result.removed;
  }

  console.log(
    `\n${dryRun ? 'would apply' : 'applied'}: `
      + `${totals.created} dashboard(s) created, ${totals.updated} updated, `
      + `${totals.tiles} tile(s) written, ${totals.removed} tile(s) removed`,
  );
}

main().catch((error) => {
  console.error(`\nFAILED: ${error.message}`);
  process.exit(1);
});
