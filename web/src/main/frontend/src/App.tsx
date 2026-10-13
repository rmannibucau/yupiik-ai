import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { call } from './api/rpc';
import { SessionsPage } from './pages/SessionsPage';
import { ChatPage } from './pages/ChatPage';
import { CodeGraphPage } from './pages/CodeGraphPage';
import { McpPage } from './pages/McpPage';
import { ToolsPage } from './pages/ToolsPage';
import { MemoryPage } from './pages/MemoryPage';

type SessionSummary = { id: string; title: string; modifiedAt: number; sizeBytes: number };

export type Tab = { id: string; title: string; mode: 'plan' | 'build' };

export function App() {
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [activeSession, setActiveSession] = useState<string | undefined>();
  const navigate = useNavigate();

  const openSession = (id: string, title: string) => {
    setTabs((prev) => {
      if (prev.some((t) => t.id === id)) return prev;
      return [...prev, { id, title, mode: 'plan' }];
    });
    setActiveSession(id);
    navigate(`/session/${id}`);
  };

  const createSession = async () => {
    const { id } = await call<{ id: string }>('sessions.create', {});
    openSession(id, 'new session');
  };

  const closeTab = (id: string) => {
    setTabs((prev) => {
      const next = prev.filter((t) => t.id !== id);
      if (activeSession === id && next.length > 0) {
        navigate(`/session/${next[next.length - 1].id}`);
        setActiveSession(next[next.length - 1].id);
      } else if (next.length === 0) {
        setActiveSession(undefined);
        navigate('/');
      }
      return next;
    });
  };

  // Clicking a tab must switch the visible session too (navigate + activate), not just the badge,
  // otherwise the tabs appear "stuck" and other tabs look lost.
  const selectTab = (id: string) => {
    setActiveSession(id);
    navigate(`/session/${id}`);
  };

  // setTabMode/setTabTitle are intentionally stable (useCallback) even though they write App state:
  // they are passed down as onModeChange/onTitleChange, and ChatPage's loadSidebar depends on
  // onModeChange's identity. If they were recreated on every App render, that dependency would
  // regenerate loadSidebar each time -> useEffect([loadSidebar]) refires -> onModeChange called ->
  // new tabs array -> App re-renders -> ... an infinite refetch/render loop that looks like the
  // page "keeps refreshing even when nothing happens" (and the mode badge flapping to plan).
  const setTabMode = useCallback((id: string, mode: 'plan' | 'build') => {
    setTabs((prev) => prev.map((t) => (t.id === id ? { ...t, mode } : t)));
  }, []);

  const setTabTitle = useCallback((id: string, title: string) => {
    const t = (title || '').trim();
    if (!t) return;
    setTabs((prev) => prev.map((tab) => (tab.id === id ? { ...tab, title: t } : tab)));
    if (activeSession === id) {
      document.title = `${t} — YCA`;
    }
  }, [activeSession]);

  return (
    <div className="flex h-full flex-col">
      <TabBar
        tabs={tabs}
        active={activeSession}
        onSelect={selectTab}
        onClose={closeTab}
        onCreate={createSession}
      />
      <div className="flex-1 overflow-hidden">
        <Routes>
          <Route path="/" element={<SessionsPage openSession={openSession} />} />
          <Route
            path="/session/:id"
            element={
              // key the page by session id so switching tabs remounts it with a clean slate:
              // the events / prompt / busy / pending-approval state never leaks across sessions.
              <ChatPage
                key={activeSession}
                activeSession={activeSession}
                openSession={openSession}
                onModeChange={setTabMode}
                onTitleChange={setTabTitle}
              />
            }
          />
          <Route path="/code-graph" element={<CodeGraphPage />} />
          <Route path="/memory" element={<MemoryPage />} />
          <Route path="/mcp" element={<McpPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>
    </div>
  );
}

const NAV_ITEMS = [
  { to: '/', label: 'Sessions' },
  { to: '/code-graph', label: 'Code graph' },
  { to: '/memory', label: 'Memory' },
  { to: '/mcp', label: 'MCP servers' },
  { to: '/tools', label: 'Tools' },
];

function TabBar({
  tabs,
  active,
  onSelect,
  onClose,
  onCreate,
}: {
  tabs: Tab[];
  active?: string;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onCreate: () => void;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const [tooltip, setTooltip] = useState<{ id: string; title: string; x: number; y: number } | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  // Dropdown root: clicking outside (or an <Esc>) closes the menu. The ref is on the wrappering
  // button + popover container so a click on the menu items themselves also counts as inside.
  const menuRef = useRef<HTMLDivElement>(null);
  const closeMenu = useCallback(() => setMenuOpen(false), []);
  useEffect(() => {
    if (!menuOpen) return;
    const onPointerDown = (e: PointerEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) closeMenu();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeMenu();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [menuOpen, closeMenu]);

  const go = (to: string) => {
    closeMenu();
    navigate(to);
  };

  return (
    <div className="relative flex items-center gap-1 border-b px-2 py-1" style={{ borderColor: 'var(--yca-border)' }}>
      <div ref={menuRef} className="relative">
        <div
          className="flex cursor-pointer items-center gap-1 rounded px-2 py-1 text-sm hover:bg-white/5"
          onMouseMove={(e) => setTooltip({ id: '__sessions', title: 'Sessions', x: e.clientX, y: e.clientY + 16 })}
          onMouseLeave={() => setTooltip(null)}
          onClick={() => setMenuOpen((o) => !o)}
          title="Sessions"
        >
          <span className="max-w-40 truncate">
            <span className="text-xs font-bold" style={{ color: 'var(--yca-muted)' }}>▤</span>{' '}
            Sessions <span className="text-[11px]" style={{ color: 'var(--yca-muted)' }}>▾</span>
          </span>
        </div>
        {menuOpen && (
          <div
            className="absolute left-0 top-full z-50 mt-1 min-w-44 overflow-hidden rounded border shadow-lg"
            style={{ background: 'var(--yca-panel)', borderColor: 'var(--yca-border)' }}
          >
            {NAV_ITEMS.map((item) => {
              const isActive = location.pathname === item.to;
              return (
                <div
                  key={item.to}
                  onClick={() => go(item.to)}
                  className={`flex cursor-pointer items-center px-3 py-1.5 text-sm ${
                    isActive ? 'text-primary' : ''
                  } hover:bg-white/5`}
                >
                  {item.label}
                </div>
              );
            })}
          </div>
        )}
      </div>
      <div className="mx-1 h-4 w-px" style={{ background: 'var(--yca-border)' }} />
      {tabs.map((tab) => (
        <div
          key={tab.id}
          className={`flex cursor-pointer items-center gap-1 rounded px-2 py-1 text-sm ${
            active === tab.id ? 'bg-white/10' : ''
          }`}
          onMouseMove={(e) => setTooltip({ id: tab.id, title: tab.title, x: e.clientX, y: e.clientY + 16 })}
          onMouseLeave={() => setTooltip(null)}
          onClick={() => {
            onSelect(tab.id);
            setTooltip(null);
          }}
          title={tab.title}
        >
          <span
            className="text-xs font-bold"
            style={{
              color: tab.mode === 'build' ? 'var(--yca-build)' : 'var(--yca-plan)',
            }}
          >
            {tab.mode === 'build' ? '◆' : '▪'}
          </span>
          <span className="max-w-40 truncate">{tab.title}</span>
          {active === tab.id && (
            <button
              className="ml-1 text-muted hover:text-red-400"
              onClick={(e) => {
                e.stopPropagation();
                onClose(tab.id);
              }}
            >
              ×
            </button>
          )}
        </div>
      ))}
      <button
        onClick={onCreate}
        className="ml-1 rounded border px-2 py-0.5 text-sm hover:bg-white/5"
        style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-primary)' }}
        title="Open a new session"
      >
        + New
      </button>
      {tooltip && (
        <div
          className="pointer-events-none absolute z-50 rounded border px-2 py-1 text-xs shadow-lg"
          style={{
            left: tooltip.x,
            top: tooltip.y,
            background: 'var(--yca-panel)',
            borderColor: 'var(--yca-border)',
            color: 'var(--yca-text)',
          }}
        >
          {tooltip.title}
        </div>
      )}
    </div>
  );
}