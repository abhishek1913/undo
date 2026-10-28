import { useState, type FormEvent } from 'react';
import { useAuth } from '../lib/auth';
import { Button } from '../components/ui';

export function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign in failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <form
        onSubmit={submit}
        className="w-full max-w-[360px] rounded-[8px] border border-line bg-surface p-6"
        aria-label="Sign in"
      >
        <h1 className="text-[20px] font-semibold">Undo</h1>
        <p className="mt-1 text-[13px] text-ink-2">
          Exactly-once actions and one-click rollback for AI agents.
        </p>
        <label className="mt-5 block text-[13px]">
          Email
          <input
            name="email"
            type="email"
            autoComplete="username"
            required
            className="mt-1 block w-full rounded-[8px] border border-line bg-bg px-3 py-2 text-[14px]"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label className="mt-3 block text-[13px]">
          Password
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            required
            className="mt-1 block w-full rounded-[8px] border border-line bg-bg px-3 py-2 text-[14px]"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && (
          <p role="alert" className="mt-3 text-[13px] text-bad">
            {error}
          </p>
        )}
        <Button type="submit" kind="primary" disabled={busy} className="mt-5 w-full">
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </main>
  );
}
