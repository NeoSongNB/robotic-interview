import type { ReactNode } from 'react';
import { Inbox } from 'lucide-react';

/**
 * 统一空态：必须有标题，可选说明与行动按钮，避免「只写一行暂无数据」却没有出口。
 * compact 用于侧栏等窄容器，保持较小留白。
 */
export function EmptyState({
  title,
  description,
  action,
  icon,
  compact = false,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  icon?: ReactNode;
  compact?: boolean;
}) {
  return (
    <div className={`flex flex-col items-center justify-center gap-3 text-center ${compact ? 'py-6' : 'py-12'}`}>
      <div className={`rounded-2xl bg-slate-100 text-slate-400 dark:bg-slate-800 dark:text-slate-500 ${compact ? 'p-2' : 'p-3'}`}>
        {icon ?? <Inbox className={compact ? 'h-5 w-5' : 'h-6 w-6'} />}
      </div>
      <p className={`font-medium text-slate-700 dark:text-slate-200 ${compact ? 'text-xs' : 'text-sm'}`}>
        {title}
      </p>
      {description && (
        <p className="max-w-sm text-xs text-slate-500 dark:text-slate-400">{description}</p>
      )}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}
