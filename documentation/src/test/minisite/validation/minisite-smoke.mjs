// Opt-in rendered-site checks. See authoring.adoc for invocation.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, readdir, stat } from 'node:fs/promises';
import { extname, resolve, relative, sep } from 'node:path';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = resolve(process.argv[2] || 'documentation/target/documentation-1.0.0-SNAPSHOT');
const base = '/fusion-coding-agent/';
const files = [];
const walk = async directory => {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      await walk(path);
    } else if (path.endsWith('.html')) {
      files.push(path);
    }
  }
};
await walk(root);
assert.ok(files.length > 40, 'Build the complete minisite before running this check.');
const html = new Map(await Promise.all(files.map(async path => [path, await readFile(path, 'utf8')])));
const errors = [];
for (const [path, content] of html) {
  for (const [, href] of content.matchAll(/\bhref="([^"]+)"/g)) {
    if (/^(?:[a-z]+:|\/\/)/i.test(href)) { continue; }
    const url = new URL(href.replaceAll('&amp;', '&'), `http://local${base}${relative(root, path).split(sep).join('/')}`);
    if (!url.pathname.startsWith(base)) { continue; }
    const target = resolve(root, decodeURIComponent(url.pathname.slice(base.length)));
    try {
      await stat(target);
      if (url.hash && html.has(target)) {
        const anchor = decodeURIComponent(url.hash.slice(1));
        if (!html.get(target).includes(`id="${anchor}"`) && !html.get(target).includes(`name="${anchor}"`)) {
          errors.push(`${relative(root, path)} -> ${href} (missing anchor)`);
        }
      }
    } catch {
      errors.push(`${relative(root, path)} -> ${href} (missing file)`);
    }
  }
  assert.ok(!/<p>\s*include::content\//.test(content), `Unresolved include in ${path}`);
}
if (errors.length) { console.error('Broken local links:', errors); }

// Scoped references must render as option definitions, not stale handwritten tables.
const scopedReferences = {
  'features/compaction.html': ['yca.compaction.enabled', 'yca.compression.contextLimit.min'],
  'features/memory.html': ['yca.memory.enabled', 'yca.memory.extractEveryTurns'],
  'features/code-graph-index.html': ['yca.structuralCodeGraph.indexEnabled', 'yca.mavenRepositoryKnowledge.codegraphIndexCacheBytes'],
  'features/ui.html': ['yca.slashcommands.btw.tools'],
  'features/context.html': ['yca.compaction.gate.maxAttempts', 'yca.pruning.nudgeMinTokens'],
  'github-bot/configuration.html': ['github.context.bind', 'github.fix.enabled', 'github.executor.docker.services.dependencyEgress.allowedHosts'],
  'slack-bot/configuration.html': ['slack.botUser', 'slack.executor.attemptRoot', 'slack.providerServices.upstream', 'slack.prompts.main', 'bot.admission.root'],
  'slack-bot/context.html': ['slack.context.enabled', 'slack.context.thread.maxMessages']
};
for (const [path, keys] of Object.entries(scopedReferences)) {
  const content = html.get(resolve(root, path));
  assert.ok(content, `Missing scoped reference page: ${path}`);
  const terms = [...content.matchAll(/<dt\b[^>]*>([\s\S]*?)<\/dt>/g)].map(match => match[1]);
  for (const key of keys) {
    const env = key.replaceAll('.', '_').toUpperCase();
    assert.ok(terms.some(term => term.includes(`<code>${key}</code>`) && term.includes(env)), `Missing generated option/environment: ${path}: ${key}`);
  }
}

const cli = html.get(resolve(root, 'cli.html'));
const bot = html.get(resolve(root, 'github-bot/index.html'));
const index = html.get(resolve(root, 'cli/index.html'));
for (const command of ['cli', 'run', 'code-graph-index', 'completion-bash', 'tui', 'web']) {
  assert.ok(cli.includes(`cli/${command}.html`), `YCA directory: ${command}`);
  assert.ok(index.includes(`${command}.html`), `Generated index: ${command}`);
}
for (const command of ['poll', 'list', 'show', 'create', 'worker', 'retry', 'delete', 'purge', 'cleanup']) {
  assert.ok(bot.includes(`../cli/github-bot-${command}.html`), `Bot directory: ${command}`);
  assert.ok(index.includes(`github-bot-${command}.html`), `Generated index: bot ${command}`);
}
assert.ok(cli.includes('cli/index.html') && bot.includes('../cli/index.html'));

const diagramMarkup = content => content.replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1>/gi, '');
const diagramPages = [...html].filter(([, content]) => diagramMarkup(content).includes('<pre class="mermaid">'));
assert.deepEqual(diagramPages.map(([path]) => relative(root, path).split(sep).join('/')).sort(), [
  'configuration.html', 'features/compaction.html', 'features/context.html',
  'features/permissions.html', 'features/sessions.html', 'github-bot/architecture.html',
  'github-bot/egress.html', 'slack-bot/deployment.html'
].sort(), 'Expected every current diagram page');
for (const [path, content] of html) {
  const scripts = [...content.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(match => match[1]).join('\n');
  const loaders = scripts.match(/await import\('https:\/\/cdn.jsdelivr.net\/npm\/mermaid@/g) || [];
  assert.equal(loaders.length, diagramPages.some(([page]) => page === path) ? 1 : 0, `Page-local loader: ${path}`);
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://local');
  const path = resolve(root, decodeURIComponent(url.pathname.slice(base.length)));
  if (!url.pathname.startsWith(base) || !path.startsWith(root + sep)) {
    response.writeHead(404).end();
    return;
  }
  try {
    response.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' })[extname(path)] || 'application/octet-stream');
    response.end(await readFile(path));
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise(done => { server.listen(0, '127.0.0.1', done); });
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({
  ...(process.env.CHROME_BIN ? { executablePath: process.env.CHROME_BIN } : {}),
  args: ['--no-sandbox']
});
try {
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', error => { pageErrors.push(error.message); });
  const rendered = async expected => {
    await page.waitForFunction(count => [...document.querySelectorAll('pre.mermaid')].filter(node => node.querySelector('svg')).length === count, expected, { timeout: 45000 });
  };
  for (const [path, content] of diagramPages) {
    const count = (content.match(/<pre class="mermaid">/g) || []).length;
    await page.goto(`${origin}${base}${relative(root, path).split(sep).join('/')}`);
    await rendered(count);
    await page.locator('#theme-toggle').click();
    await page.evaluate(() => new Promise(done => { requestAnimationFrame(() => { requestAnimationFrame(done); }); }));
    await rendered(count);
    await page.evaluate(() => {
      document.documentElement.setAttribute('data-theme', 'light');
      setTimeout(() => { document.documentElement.setAttribute('data-theme', 'dark'); }, 0);
      setTimeout(() => { document.documentElement.setAttribute('data-theme', 'light'); }, 10);
    });
    await page.waitForTimeout(300);
    await rendered(count);
    assert.equal(await page.locator('[role="status"]').filter({ hasText: 'Diagram unavailable.' }).count(), 0);
    console.log(`Diagrams passed: ${relative(root, path)} (${count}, both themes and rapid toggles)`);
  }

  await page.goto(`${origin}${base}configuration.html`);
  const input = page.getByRole('searchbox', { name: 'Filter configuration options by key' });
  await input.focus();
  await page.keyboard.type('NO_SUCH_CONFIGURATION_KEY_12345');
  await page.waitForFunction(() => document.getElementById('config-filter-status').textContent === '0 matching options.');
  await input.fill('provider');
  await page.waitForFunction(() => parseInt(document.getElementById('config-filter-status').textContent, 10) > 0);
  await input.fill('');
  assert.equal(await page.locator('details').count(), 1, 'Schema disclosure');
  assert.equal(await page.locator('details').getAttribute('open'), null);
  await page.locator('details > summary').focus();
  await page.keyboard.press('Enter');
  assert.notEqual(await page.locator('details').getAttribute('open'), null);

  await page.setViewportSize({ width: 390, height: 844 });
  for (const path of ['github-bot/index.html', 'github-bot/security.html', 'cli.html', 'features.html', 'libraries.html']) {
    await page.goto(`${origin}${base}${path}`);
    const metrics = await page.evaluate(() => ({
      width: innerWidth,
      scroll: document.documentElement.scrollWidth,
      longestParagraph: Math.max(0, ...[...document.querySelectorAll('.page-content-body .paragraph > p')].map(node => node.textContent.trim().split(/\s+/).length))
    }));
    assert.ok(metrics.scroll <= metrics.width + 1, `Mobile page overflow: ${path} (${metrics.scroll})`);
    assert.ok(metrics.longestParagraph <= 120, `Dense paragraph: ${path} (${metrics.longestParagraph} words)`);
  }

  const offline = await browser.newPage();
  await offline.route('**/npm/mermaid@**', route => { void route.abort(); });
  await offline.goto(`${origin}${base}github-bot/architecture.html`);
  await offline.getByRole('status').first().waitFor();
  assert.equal(await offline.locator('pre.mermaid svg').count(), 0);
  assert.ok((await offline.locator('pre.mermaid').first().textContent()).includes('flowchart TB'));
  await offline.close();

  // A malformed diagram must not prevent the other diagrams on the page rendering.
  const malformed = await browser.newPage();
  await malformed.route('**/github-bot/architecture.html', async route => {
    const response = await route.fetch();
    const body = (await response.text()).replace(/(<pre class="mermaid">)[\s\S]*?(<\/pre>)/, '$1not-a-diagram$2');
    await route.fulfill({ response, body });
  });
  await malformed.goto(`${origin}${base}github-bot/architecture.html`);
  await malformed.waitForFunction(() => document.querySelectorAll('pre.mermaid svg').length === 3);
  assert.ok((await malformed.locator('pre.mermaid').first().textContent()).includes('not-a-diagram'));
  assert.equal(await malformed.getByRole('status').count(), 1);
  await malformed.close();
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(errors, [], 'Broken local links');
  console.log(`Passed ${files.length} pages: local links/anchors, command directories, diagrams, fallback, mobile, keyboard and filter checks.`);
} finally {
  await browser.close();
  server.close();
}
