import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown } from 'lucide-react';

export type AnchoredOption = { value: string; label: string };

type Props = {
  value: string;
  onChange: (value: string) => void;
  options?: AnchoredOption[];
  placeholder?: string;
  disabled?: boolean;
  ariaLabel?: string;
};

const GAP = 4;
const EDGE = 8;
const PANEL_MAX = 240;

/**
 * 可输入、可选择的模型下拉框。
 *
 * 选项面板通过 Portal 挂到 document.body，并用 position: fixed + 触发框的
 * getBoundingClientRect() 定位：面板不会被弹窗的 overflow / transform /
 * 动画裁切或带偏。滚动、resize 时重新锚定；空间不足时自动向上翻转，
 * 始终完整留在视口内。
 */
export default function AnchoredSelect({
  value,
  onChange,
  options,
  placeholder,
  disabled = false,
  ariaLabel,
}: Props) {
  const triggerRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [style, setStyle] = useState<CSSProperties | null>(null);
  const hasOptions = !!options && options.length > 0;

  const updatePosition = useCallback(() => {
    const el = triggerRef.current;
    if (!el) {
      setStyle(null);
      return;
    }
    const r = el.getBoundingClientRect();
    const vh = window.innerHeight;
    const vw = window.innerWidth;

    // 触发框完全滚出视口时收起，避免面板孤零零悬在屏幕上
    if (r.bottom <= 0 || r.top >= vh) {
      setOpen(false);
      return;
    }

    const spaceBelow = vh - r.bottom - GAP - EDGE;
    const spaceAbove = r.top - GAP - EDGE;
    const wantBelow = Math.min(PANEL_MAX, options ? options.length * 42 + 8 : PANEL_MAX);
    const below = spaceBelow >= Math.min(wantBelow, 140) || spaceBelow >= spaceAbove;

    const left = Math.max(EDGE, Math.min(r.left, vw - r.width - EDGE));
    const width = Math.min(r.width, vw - EDGE * 2);

    setStyle(below
      ? { position: 'fixed', left, width, top: r.bottom + GAP, maxHeight: Math.max(96, Math.min(PANEL_MAX, spaceBelow)) }
      : { position: 'fixed', left, width, bottom: vh - r.top + GAP, maxHeight: Math.max(96, Math.min(PANEL_MAX, spaceAbove)) });
  }, [options]);

  useLayoutEffect(() => {
    if (!open) {
      setStyle(null);
      return;
    }
    updatePosition();
    // capture: true 才能捕获弹窗内部滚动容器的 scroll 事件
    window.addEventListener('scroll', updatePosition, true);
    window.addEventListener('resize', updatePosition);
    return () => {
      window.removeEventListener('scroll', updatePosition, true);
      window.removeEventListener('resize', updatePosition);
    };
  }, [open, updatePosition]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (triggerRef.current?.contains(target) || panelRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown, true);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown, true);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  // 禁用或没有预设选项时收起面板
  useEffect(() => {
    if (disabled || !hasOptions) setOpen(false);
  }, [disabled, hasOptions]);

  const inputClass = 'w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 '
    + 'bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white '
    + 'placeholder:text-slate-400 focus:outline-none focus:ring-2 '
    + 'focus:ring-primary-500/50 focus:border-primary-400 transition-shadow '
    + 'disabled:cursor-not-allowed disabled:opacity-60';

  return (
    <>
      <div className="relative" ref={triggerRef}>
        <input
          type="text"
          aria-label={ariaLabel}
          value={value}
          disabled={disabled}
          onFocus={() => hasOptions && !disabled && setOpen(true)}
          onChange={(e) => {
            onChange(e.target.value);
            setOpen(false);
          }}
          placeholder={placeholder}
          className={`${inputClass} ${hasOptions ? 'pr-10' : ''}`}
        />
        {hasOptions && (
          <button
            type="button"
            aria-label="展开模型列表"
            onClick={() => setOpen((v) => !v)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
              hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
          >
            <ChevronDown className="w-4 h-4" />
          </button>
        )}
      </div>

      {open && hasOptions && style && createPortal(
        <div
          ref={panelRef}
          style={{ ...style, zIndex: 70 }}
          onPointerDown={(e) => e.preventDefault()}
          className="overflow-y-auto overscroll-contain bg-white dark:bg-slate-700
            border border-slate-200 dark:border-slate-600 rounded-xl shadow-xl"
        >
          {options!.map((option) => (
            <button
              key={option.value}
              type="button"
              onClick={() => {
                onChange(option.value);
                setOpen(false);
              }}
              className={`w-full px-4 py-2.5 text-left text-sm hover:bg-primary-50
                dark:hover:bg-slate-600 transition-colors flex justify-between items-center
                ${value === option.value
                  ? 'text-primary-600 dark:text-primary-400 font-medium bg-primary-50 dark:bg-slate-600'
                  : 'text-slate-700 dark:text-slate-200'}`}
            >
              <span className="font-mono">{option.value}</span>
              <span className="text-xs text-slate-400 dark:text-slate-500 ml-2 whitespace-nowrap">{option.label}</span>
            </button>
          ))}
        </div>,
        document.body,
      )}
    </>
  );
}
