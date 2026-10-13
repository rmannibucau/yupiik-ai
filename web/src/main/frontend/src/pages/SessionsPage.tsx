import { useEffect, useMemo, useRef, useState } from 'react';
import { call } from '../api/rpc';

type SessionSummary = { id: string; title: string; modifiedAt: number; sizeBytes: number; project?: string };

export function SessionsPage({ openSession }: { openSession: (id: string, title: string) => void }) {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [query, setQuery] = useState('');
  const [project, setProject] = useState('');
  const [loading, setLoading] = useState(true);
  // skip the mount run of the query-change effect (mount already loads once below)
  const isFirstRun = useRef(true);

  const load = () => {
    setLoading(true);
    // project='' disables the backend's default current-project scoping so the home page lists every
    // session, then groups them by working directory client-side below.
    call<SessionSummary[]>('sessions.list', { query, project: '' })
      .then(setSessions)
      .finally(() => setLoading(false));
  };

  // immediate load on mount, then debounced reloads as the search query is typed so a fast typist
  // doesn't fire one /api/rpc round-trip per keystroke.
  useEffect(() => {
    load();
    isFirstRun.current = false;
  }, []);

  useEffect(() => {
    if (isFirstRun.current) return;
    if (query === '') {
      load();
      return;
    }
    const t = window.setTimeout(load, 200);
    return () => window.clearTimeout(t);
  }, [query]);

  useEffect(() => {
    void call<{ project: string }>('sessions.currentProject', {}).then((r) => setProject(r.project));
  }, []);

  const create = async () => {
    const { id } = await call<{ id: string }>('sessions.create', {});
    openSession(id, 'new session');
  };

  // group the flat list by working directory (falling back to an "unknown" bucket), each group
  // sorted by last-modified desc so the current work sits on top within its project.
  const groups = useMemo(() => {
    const byProject = new Map<string, SessionSummary[]>();
    for (const s of sessions) {
      const key = (s.project || '').trim() || '(unknown project)';
      const list = byProject.get(key);
      if (list) list.push(s);
      else byProject.set(key, [s]);
    }
    const sorted = [...byProject.entries()].sort((a, b) => {
      const ma = (a[1][0]?.modifiedAt ?? 0) - (b[1][0]?.modifiedAt ?? 0);
      if (ma !== 0) return -ma;
      return a[0].localeCompare(b[0]);
    });
    return sorted.map(([projectDir, list]) => ({
      projectDir,
      sessions: [...list].sort((a, b) => (b.modifiedAt ?? 0) - (a.modifiedAt ?? 0)),
    }));
  }, [sessions]);

  return (
    <div className="mx-auto h-full max-w-3xl overflow-y-auto p-6">
      <div className="yca-page-header flex items-center gap-2">
        <h1 className="flex-1">Sessions</h1>
        {project && (
          <span
            className="max-w-xs truncate rounded border px-2 py-0.5 text-xs"
            style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-muted)' }}
          >
            {project}
          </span>
        )}
        <button onClick={create} className="rounded border px-3 py-1 text-sm hover:bg-white/5" style={{ borderColor: 'var(--yca-primary)', color: 'var(--yca-primary)' }}>
          + New session
        </button>
      </div>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search sessions…"
        className="mb-4 w-full rounded border px-3 py-2 text-sm outline-none"
        style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}
      />
      {loading ? (
        <p className="text-sm" style={{ color: 'var(--yca-muted)' }}>
          Loading…
        </p>
      ) : groups.length === 0 ? (
        <p className="text-sm" style={{ color: 'var(--yca-muted)' }}>
          No sessions.
        </p>
      ) : (
        <div className="space-y-4">
          {groups.map((group) => (
            <section key={group.projectDir}>
              <h2
                className="mb-1 truncate text-xs font-semibold uppercase tracking-wide"
                style={{ color: 'var(--yca-muted)' }}
                title={group.projectDir === '(unknown project)' ? undefined : group.projectDir}
              >
                {group.projectDir}
                <span className="ml-2 font-normal normal-case opacity-70">{group.sessions.length}</span>
              </h2>
              <ul className="space-y-1">
                {group.sessions.map((s) => (
                  <li key={s.id}>
                    <button
                      onClick={() => openSession(s.id, s.title)}
                      className="yca-card block w-full px-3 py-2 text-left text-[15px]"
                    >
                      <div className="truncate font-medium">{s.title || s.id}</div>
                      <div className="truncate text-xs" style={{ color: 'var(--yca-muted)' }}>
                        {s.id} · {formatSize(s.sizeBytes)}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}