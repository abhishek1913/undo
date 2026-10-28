export type Theme = 'light' | 'dark';

const KEY = 'undo.theme';

export function currentTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

export function setTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    // storage may be unavailable; the theme still applies for this page view
  }
}

export function applyStoredTheme() {
  let stored: string | null;
  try {
    stored = localStorage.getItem(KEY);
  } catch {
    stored = null;
  }
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  setTheme(stored === 'dark' || (stored === null && prefersDark) ? 'dark' : 'light');
}
