import * as Dialog from '@radix-ui/react-dialog';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import { ConfirmDialog } from '../components/Dialogs';
import { useToast } from '../components/Toasts';
import {
  Button,
  Card,
  Empty,
  ErrorBox,
  Loading,
  Mono,
  PageTitle,
  StatusPill,
  Table,
  Td,
  Time,
} from '../components/ui';

type Key = {
  id: string;
  prefix: string;
  allowed_tools: string[];
  default_policy: string;
  created_at: string;
  revoked_at: string | null;
};
type AgentRow = { id: string; name: string; created_at: string; keys: Key[] };
type Created = { id: string; key: string; prefix: string; mcp_command: string };

export function KeysPage() {
  const { has } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const q = useQuery({
    queryKey: ['agents'],
    queryFn: () => api<{ data: AgentRow[] }>('/api/agents'),
    enabled: has('ADMIN'),
  });
  if (!has('ADMIN')) {
    return (
      <div>
        <PageTitle>Settings · Agents and keys</PageTitle>
        <Empty>Only an admin can manage agents and API keys.</Empty>
      </div>
    );
  }
  return (
    <div>
      <PageTitle sub="Agents call Undo with an API key. Undo stores only a SHA-256 hash of each key.">
        Settings · Agents and keys
      </PageTitle>
      <Card title="New agent" className="mb-5">
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={async (e) => {
            e.preventDefault();
            setError(null);
            try {
              await api('/api/agents', { method: 'POST', json: { name: name.trim() } });
              setName('');
              toast('Agent created');
              qc.invalidateQueries({ queryKey: ['agents'] });
            } catch (err) {
              setError(err instanceof Error ? err.message : String(err));
            }
          }}
        >
          <label className="text-[13px]">
            Name
            <input
              className="mt-1 block w-64 rounded-[8px] border border-line bg-bg p-2 text-[14px]"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="support-bot"
            />
          </label>
          <Button kind="primary" type="submit" disabled={!name.trim()}>
            Create agent
          </Button>
          {error && (
            <p role="alert" className="w-full text-[13px] text-bad">
              {error}
            </p>
          )}
        </form>
      </Card>
      {q.isLoading ? (
        <Loading />
      ) : q.error ? (
        <ErrorBox error={q.error} />
      ) : q.data!.data.length === 0 ? (
        <Empty>No agents yet.</Empty>
      ) : (
        <div className="space-y-4">
          {q.data!.data.map((a) => (
            <Card key={a.id} title={a.name} actions={<CreateKeyDialog agent={a} />}>
              {a.keys.length === 0 ? (
                <p className="text-ink-2">No keys.</p>
              ) : (
                <Table
                  label={`Keys of ${a.name}`}
                  head={['Key', 'Allowed tools', 'Default policy', 'Created', 'Status', '']}
                >
                  {a.keys.map((k) => (
                    <tr key={k.id}>
                      <Td>
                        <Mono>{k.prefix}…</Mono>
                      </Td>
                      <Td className="font-mono text-[12px]">
                        {k.allowed_tools.length ? k.allowed_tools.join(', ') : 'all'}
                      </Td>
                      <Td>{k.default_policy}</Td>
                      <Td>
                        <Time iso={k.created_at} />
                      </Td>
                      <Td>
                        {k.revoked_at ? (
                          <StatusPill status="REVOKED" tone="muted" />
                        ) : (
                          <StatusPill status="ACTIVE" tone="ok" />
                        )}
                      </Td>
                      <Td>
                        {!k.revoked_at && (
                          <ConfirmDialog
                            trigger={<Button kind="danger">Revoke</Button>}
                            title={`Revoke key ${k.prefix}…?`}
                            description="Calls with this key fail at once. This cannot be undone."
                            confirmLabel="Revoke key"
                            onConfirm={async () => {
                              await api(`/api/keys/${k.id}/revoke`, { method: 'POST', json: {} });
                              toast('Key revoked');
                              qc.invalidateQueries({ queryKey: ['agents'] });
                            }}
                          />
                        )}
                      </Td>
                    </tr>
                  ))}
                </Table>
              )}
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

function CreateKeyDialog({ agent }: { agent: AgentRow }) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [tools, setTools] = useState('*');
  const [policy, setPolicy] = useState('support-default');
  const [created, setCreated] = useState<Created | null>(null);
  const [error, setError] = useState<string | null>(null);
  function close(o: boolean) {
    setOpen(o);
    if (!o) {
      setCreated(null);
      setTools('*');
      setError(null);
    }
  }
  return (
    <Dialog.Root open={open} onOpenChange={close}>
      <Dialog.Trigger asChild>
        <Button>Create key</Button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/40" />
        <Dialog.Content className="fixed left-1/2 top-1/2 w-[min(92vw,560px)] -translate-x-1/2 -translate-y-1/2 rounded-[8px] border border-line bg-surface p-5 shadow-lg focus:outline-none">
          <Dialog.Title className="text-[16px] font-semibold">
            New key for {agent.name}
          </Dialog.Title>
          {created ? (
            <div>
              <Dialog.Description className="mt-2 text-[14px] text-bad">
                This is the only time the full key is shown. Copy it now.
              </Dialog.Description>
              <CopyRow label="API key" value={created.key} />
              <CopyRow label="Claude Code" value={created.mcp_command} />
              <div className="mt-5 flex justify-end">
                <Dialog.Close asChild>
                  <Button kind="primary">Done</Button>
                </Dialog.Close>
              </div>
            </div>
          ) : (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                setError(null);
                try {
                  const allowed = tools
                    .split(',')
                    .map((t) => t.trim())
                    .filter(Boolean);
                  const c = await api<Created>(`/api/agents/${agent.id}/keys`, {
                    method: 'POST',
                    json: { allowed_tools: allowed, default_policy: policy },
                  });
                  setCreated(c);
                  qc.invalidateQueries({ queryKey: ['agents'] });
                } catch (err) {
                  setError(err instanceof Error ? err.message : String(err));
                }
              }}
            >
              <Dialog.Description className="mt-2 text-[14px] text-ink-2">
                Patterns are globs: * allows every tool, shop_get_* only the reads.
              </Dialog.Description>
              <label className="mt-4 block text-[13px]">
                Allowed tools (comma separated)
                <input
                  className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 font-mono text-[13px]"
                  value={tools}
                  onChange={(e) => setTools(e.target.value)}
                  placeholder="*"
                />
              </label>
              <label className="mt-3 block text-[13px]">
                Default policy
                <input
                  className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 font-mono text-[13px]"
                  value={policy}
                  onChange={(e) => setPolicy(e.target.value)}
                />
              </label>
              {error && (
                <p role="alert" className="mt-3 text-[13px] text-bad">
                  {error}
                </p>
              )}
              <div className="mt-5 flex justify-end gap-2">
                <Dialog.Close asChild>
                  <Button>Cancel</Button>
                </Dialog.Close>
                <Button kind="primary" type="submit" disabled={!policy.trim() || !tools.trim()}>
                  Create key
                </Button>
              </div>
            </form>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function CopyRow({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="mt-4">
      <div className="text-[13px] text-ink-2">{label}</div>
      <div className="mt-1 flex items-start gap-2">
        <code
          className="block min-w-0 flex-1 break-all rounded-[8px] bg-surface-2 p-2 font-mono text-[12px]"
          data-testid={`copy-${label}`}
        >
          {value}
        </code>
        <Button
          ariaLabel={`Copy ${label}`}
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(value);
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            } catch {
              setCopied(false);
            }
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
    </div>
  );
}
