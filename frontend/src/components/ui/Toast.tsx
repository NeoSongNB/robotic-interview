import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, XCircle, Info, X } from 'lucide-react';

export type ToastKind = 'success' | 'error' | 'info';

type ToastItem = { id: string; kind: ToastKind; message: string };

const TOAST_EVENT = 'app:toast';
const DISMISS_EVENT = 'app:toast-dismiss';

/**
 * 用 window 自定义事件作为总线，而不是模块级变量。
 *
 * 同一模块在 Vite HMR 下可能以不同 URL 被加载多次，模块级 store 会各自独立，
 * 导致 toast() 静默丢失；事件总线挂在 window 上，跨实例一定可达。
 * 这样页面组件、hooks 与普通工具函数都能直接调用，无需 React 上下文。
 */
export function toast(kind: ToastKind, message: string) {
  if (typeof window === 'undefined') return;
  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const detail: ToastItem = { id, kind, message };
  window.dispatchEvent(new CustomEvent(TOAST_EVENT, { detail }));
  window.setTimeout(() => dismiss(id), 4200);
}

export function dismiss(id: string) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(DISMISS_EVENT, { detail: { id } }));
}

const ICONS = { success: CheckCircle2, error: XCircle, info: Info };
const STYLES: Record<ToastKind, string> = {
  success: 'bg-emerald-600 text-white',
  error: 'bg-red-600 text-white',
  info: 'bg-slate-800 text-white dark:bg-slate-700',
};

export function ToastViewport() {
  const [items, setItems] = useState<ToastItem[]>([]);

  useEffect(() => {
    const onToast = (event: Event) => {
      const item = (event as CustomEvent<ToastItem>).detail;
      setItems((prev) => [...prev, item]);
    };
    const onDismiss = (event: Event) => {
      const { id } = (event as CustomEvent<{ id: string }>).detail;
      setItems((prev) => prev.filter((item) => item.id !== id));
    };
    window.addEventListener(TOAST_EVENT, onToast);
    window.addEventListener(DISMISS_EVENT, onDismiss);
    return () => {
      window.removeEventListener(TOAST_EVENT, onToast);
      window.removeEventListener(DISMISS_EVENT, onDismiss);
    };
  }, []);

  if (items.length === 0 || typeof document === 'undefined') return null;

  return createPortal(
    <div
      role="status"
      aria-live="polite"
      data-testid="toast-viewport"
      className="pointer-events-none fixed bottom-6 left-1/2 z-[80] flex -translate-x-1/2 flex-col items-center gap-2"
    >
      {items.map((item) => {
        const Icon = ICONS[item.kind];
        return (
          <div
            key={item.id}
            className={`pointer-events-auto flex max-w-[90vw] items-center gap-2 rounded-xl px-5 py-3 text-sm font-medium shadow-lg ${STYLES[item.kind]}`}
          >
            <Icon className="h-4 w-4 shrink-0" />
            <span>{item.message}</span>
            <button
              type="button"
              onClick={() => dismiss(item.id)}
              aria-label="关闭提示"
              className="ml-2 opacity-70 transition-opacity hover:opacity-100"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        );
      })}
    </div>,
    document.body,
  );
}
