import * as Dialog from '@radix-ui/react-dialog';
import { useState, type ReactNode } from 'react';
import { Button } from './ui';

/** Confirmation for destructive actions (FR-UI-3). Optional typed confirmation and note. */
export function ConfirmDialog({
  trigger,
  title,
  description,
  confirmLabel,
  onConfirm,
  requireText,
  withNote,
  noteRequired,
  danger = true,
}: {
  trigger: ReactNode;
  title: string;
  description: ReactNode;
  confirmLabel: string;
  onConfirm: (note: string) => Promise<void> | void;
  requireText?: string;
  withNote?: boolean;
  noteRequired?: boolean;
  danger?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ok =
    (!requireText || typed.trim() === requireText) && (!noteRequired || note.trim().length > 0);
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(o) => {
        setOpen(o);
        if (!o) {
          setTyped('');
          setNote('');
          setError(null);
        }
      }}
    >
      <Dialog.Trigger asChild>{trigger}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/40" />
        <Dialog.Content className="fixed left-1/2 top-1/2 w-[min(92vw,460px)] -translate-x-1/2 -translate-y-1/2 rounded-[8px] border border-line bg-surface p-5 shadow-lg focus:outline-none">
          <Dialog.Title className="text-[16px] font-semibold">{title}</Dialog.Title>
          <Dialog.Description className="mt-2 text-[14px] text-ink-2">
            {description}
          </Dialog.Description>
          {withNote && (
            <label className="mt-4 block text-[13px]">
              Note{noteRequired ? ' (required)' : ''}
              <textarea
                className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 text-[14px]"
                rows={3}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                autoFocus
              />
            </label>
          )}
          {requireText && (
            <label className="mt-4 block text-[13px]">
              Type <span className="font-mono font-semibold">{requireText}</span> to confirm
              <input
                className="mt-1 block w-full rounded-[8px] border border-line bg-bg p-2 font-mono text-[14px]"
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                autoFocus={!withNote}
              />
            </label>
          )}
          {error && (
            <p role="alert" className="mt-3 text-[13px] text-bad">
              {error}
            </p>
          )}
          <div className="mt-5 flex justify-end gap-2">
            <Dialog.Close asChild>
              <Button>Cancel</Button>
            </Dialog.Close>
            <Button
              kind={danger ? 'danger' : 'primary'}
              disabled={!ok || busy}
              onClick={async () => {
                setBusy(true);
                setError(null);
                try {
                  await onConfirm(note.trim());
                  setOpen(false);
                } catch (e) {
                  setError(e instanceof Error ? e.message : String(e));
                } finally {
                  setBusy(false);
                }
              }}
            >
              {confirmLabel}
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
