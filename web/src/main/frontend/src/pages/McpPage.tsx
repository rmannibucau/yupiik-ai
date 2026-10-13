import { useEffect, useState } from 'react';
import { call } from '../api/rpc';
import { PageShell } from './PageShell';

type McpStatus = { name: string; state: string }[];

export function McpPage() {
  const [servers, setServers] = useState<McpStatus | null>(null);

  const load = () => {
    setServers(null);
    call<McpStatus>('mcp.status', {}).then(setServers).catch(() => setServers([]));
  };

  useEffect(load, []);

  const reset = async (name?: string) => {
    await call('mcp.reset', name ? { name } : {});
    load();
  };

  return (
    <PageShell title="MCP servers">
      <ul className="space-y-2">
        {(servers ?? []).map((s) => (
          <li
            key={s.name}
            className="yca-card flex items-center justify-between px-3 py-2"
          >
            <span>
              <span
                className="mr-2 inline-block h-2 w-2 rounded-full"
                style={{ background: s.state === 'RUNNING' ? 'var(--yca-accent)' : '#888' }}
              />
              {s.name}
              <span className="ml-2 text-xs" style={{ color: 'var(--yca-muted)' }}>{s.state}</span>
            </span>
            <button onClick={() => reset(s.name)} className="rounded border px-2 py-0.5 text-xs" style={{ borderColor: 'var(--yca-border)' }}>
              reset
            </button>
          </li>
        ))}
        {servers && servers.length === 0 && <p style={{ color: 'var(--yca-muted)' }}>No MCP servers.</p>}
      </ul>
      <button onClick={() => reset()} className="mt-4 rounded border px-3 py-1" style={{ borderColor: 'var(--yca-border)' }}>
        Reset all
      </button>
    </PageShell>
  );
}