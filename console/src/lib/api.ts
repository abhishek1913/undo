/** Fetch wrapper for /api: session cookie, CSRF double-submit header, problem+json errors. */

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public body: unknown = null,
  ) {
    super(message);
  }
}

function readCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1] ?? '') : null;
}

let csrfToken: string | null = null;

export function setCsrf(token: string | null) {
  csrfToken = token;
}

export async function api<T = unknown>(
  path: string,
  init: RequestInit & { json?: unknown } = {},
): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (init.json !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const t = csrfToken ?? readCookie('undo_csrf');
    if (t) headers.set('X-CSRF-Token', t);
  }
  const res = await fetch(path, {
    ...init,
    method,
    headers,
    credentials: 'same-origin',
    body: init.json !== undefined ? JSON.stringify(init.json) : init.body,
  });
  const text = await res.text();
  const body = text ? safeParse(text) : null;
  if (!res.ok) {
    const b = (body ?? {}) as { code?: string; detail?: string; title?: string };
    if (res.status === 401 && !path.startsWith('/api/auth/login')) {
      window.dispatchEvent(new CustomEvent('undo:unauthenticated'));
    }
    throw new ApiError(
      res.status,
      b.code ?? String(res.status),
      b.detail ?? b.title ?? res.statusText,
      body,
    );
  }
  return body as T;
}

function safeParse(t: string): unknown {
  try {
    return JSON.parse(t);
  } catch {
    return t;
  }
}
