import type { HTMLAttributes } from 'react';

/** 统一卡片容器，复用 .dark-card 玻璃拟态规范。 */
export function Card({ className = '', ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div {...rest} className={`dark-card rounded-2xl p-5 ${className}`} />;
}
