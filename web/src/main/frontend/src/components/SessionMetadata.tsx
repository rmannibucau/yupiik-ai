import { useEffect, useId, useState } from 'react';

export interface SessionMetadataProps {
  sessionId: string;
  project?: string;
  gitBranch?: string;
  model?: string;
  flavor?: string;
  provider?: string;
  permissionMode?: string;
  modelCandidates?: string[];
  providerCandidates?: string[];
  onSetProject: (dir: string) => void;
  onSetModel: (model: string) => void;
  onSetProvider: (provider: string) => void;
  onSetPermissions: (mode: string) => void;
}

export function SessionMetadata({
  sessionId, project, gitBranch, model, flavor, provider, permissionMode,
  modelCandidates, providerCandidates, onSetProject, onSetModel, onSetProvider, onSetPermissions,
}: SessionMetadataProps) {
  const id = useId();
  const [dirDraft, setDirDraft] = useState(project ?? '');
  useEffect(() => {
    setDirDraft(project ?? '');
  }, [project]);

  return (
    <dl className="yca-session-metadata">
      <div className="yca-metadata-row">
        <dt>Session</dt>
        <dd className="yca-metadata-value" title={sessionId}>{sessionId}</dd>
      </div>
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-project`}>Working dir</label></dt>
        <dd className="yca-metadata-edit">
          <input
            id={`${id}-project`}
            className="yca-metadata-control"
            value={dirDraft}
            title={dirDraft}
            onChange={(e) => setDirDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                onSetProject(dirDraft);
              }
            }}
          />
          <button type="button" onClick={() => onSetProject(dirDraft)}>Set</button>
        </dd>
      </div>
      {gitBranch && (
        <div className="yca-metadata-row">
          <dt>Branch</dt>
          <dd className="yca-metadata-value" title={gitBranch}>{gitBranch}</dd>
        </div>
      )}
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-provider`}>Provider</label></dt>
        <dd>
          <select
            id={`${id}-provider`}
            className="yca-metadata-control"
            value={provider || ''}
            title={provider}
            onChange={(e) => {
              if (e.target.value) {
                onSetProvider(e.target.value);
              }
            }}
          >
            {provider && <option value={provider}>{provider}</option>}
            {(providerCandidates ?? []).filter((p) => p !== provider).map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </select>
        </dd>
      </div>
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-model`}>Model</label></dt>
        <dd>
          <select
            id={`${id}-model`}
            className="yca-metadata-control"
            value={model || ''}
            title={model}
            onChange={(e) => {
              if (e.target.value) {
                onSetModel(e.target.value);
              }
            }}
          >
            {model && <option value={model}>{model}</option>}
            {(modelCandidates ?? []).filter((m) => m !== model).map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </dd>
      </div>
      {flavor && (
        <div className="yca-metadata-row">
          <dt>Flavor</dt>
          <dd className="yca-metadata-value" title={flavor}>{flavor}</dd>
        </div>
      )}
      <div className="yca-metadata-row">
        <dt><label htmlFor={`${id}-permissions`}>Permissions</label></dt>
        <dd className="yca-metadata-edit">
          <select
            id={`${id}-permissions`}
            className="yca-metadata-control"
            value={permissionMode || 'ask'}
            title="Tool permission level for this session"
            onChange={(e) => {
              if (e.target.value) {
                onSetPermissions(e.target.value);
              }
            }}
          >
            <option value="ask">ask</option>
            <option value="auto_edit">auto_edit</option>
            <option value="yolo">yolo</option>
          </select>
          <span
            aria-hidden="true"
            className="inline-block h-2 w-2 shrink-0 rounded-full"
            style={{
              background: permissionMode === 'yolo'
                ? 'var(--yca-yolo)'
                : permissionMode === 'auto_edit'
                ? 'var(--yca-success)'
                : permissionMode === 'ask'
                ? 'var(--yca-plan)'
                : 'var(--yca-muted)',
            }}
          />
        </dd>
      </div>
    </dl>
  );
}
