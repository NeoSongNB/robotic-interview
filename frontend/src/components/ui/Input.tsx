import { forwardRef } from 'react';
import type { InputHTMLAttributes } from 'react';

/** 统一输入框，复用 .dark-input 规范。 */
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = '', ...rest }, ref) {
    return (
      <input
        ref={ref}
        {...rest}
        className={`dark-input w-full rounded-xl px-4 py-2.5 text-sm outline-none ${className}`}
      />
    );
  },
);
