import * as Toast from '@radix-ui/react-toast';
import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';

type Item = { id: number; text: string; tone: 'ok' | 'bad' };
const Ctx = createContext<(text: string, tone?: 'ok' | 'bad') => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Item[]>([]);
  const push = useCallback((text: string, tone: 'ok' | 'bad' = 'ok') => {
    setItems((xs) => [...xs, { id: Date.now() + Math.random(), text, tone }]);
  }, []);
  return (
    <Ctx.Provider value={push}>
      <Toast.Provider swipeDirection="right" duration={4000}>
        {children}
        {items.map((t) => (
          <Toast.Root
            key={t.id}
            onOpenChange={(o) => !o && setItems((xs) => xs.filter((x) => x.id !== t.id))}
            className={`rounded-[8px] border bg-surface px-4 py-3 text-[14px] shadow ${t.tone === 'bad' ? 'border-bad/60 text-bad' : 'border-line'}`}
          >
            <Toast.Description>{t.text}</Toast.Description>
          </Toast.Root>
        ))}
        <Toast.Viewport className="fixed bottom-4 right-4 z-50 flex w-[min(92vw,360px)] flex-col gap-2" />
      </Toast.Provider>
    </Ctx.Provider>
  );
}

export function useToast() {
  return useContext(Ctx);
}
