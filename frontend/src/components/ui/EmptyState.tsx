import type { ReactNode } from 'react';
import { Inbox } from 'lucide-react';

/** 统一空态：必须有标题，可选说明与行动按钮，避免「只写一行暂无数据」。 */
export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
      <div className="rounded-2xl bg-slate-100 p-3 text-slate-400 dark:bg-slate-800 dark:text-slate-500">
        {icon ?? <Inbox className="h-6 w-6" />}
      </div>
      <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{title}</p>
      {description && (
        <p className="max-w-sm text-xs text-slate-500 dark:text-slate-400">{description}</p>
      )}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}
