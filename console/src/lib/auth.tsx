import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, setCsrf } from './api';

export type User = { id: string; email: string; roles: string[] };

type AuthState = {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  has: (role: string) => boolean;
};

const Ctx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api<{ user: User }>('/api/auth/me')
      .then((r) => setUser(r.user))
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
    const onLost = () => setUser(null);
    window.addEventListener('undo:unauthenticated', onLost);
    return () => window.removeEventListener('undo:unauthenticated', onLost);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const r = await api<{ user: User; csrf_token: string }>('/api/auth/login', {
      method: 'POST',
      json: { email, password },
    });
    setCsrf(r.csrf_token);
    setUser(r.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await api('/api/auth/logout', { method: 'POST', json: {} });
    } finally {
      setCsrf(null);
      setUser(null);
    }
  }, []);

  const has = useCallback(
    (role: string) =>
      !!user && (user.roles.includes('ADMIN') || user.roles.includes(role) || role === 'VIEWER'),
    [user],
  );

  return <Ctx.Provider value={{ user, loading, login, logout, has }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthState {
  const c = useContext(Ctx);
  if (!c) throw new Error('useAuth outside AuthProvider');
  return c;
}
