/** 骨架屏基元；prefers-reduced-motion 由 index.css 统一关闭动画。 */
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={`animate-pulse rounded-lg bg-slate-200/70 dark:bg-slate-700/50 ${className}`}
    />
  );
}

export function SkeletonCard({ lines = 3 }: { lines?: number }) {
  return (
    <div className="dark-card space-y-3 rounded-2xl p-5" role="status" aria-label="内容加载中">
      <Skeleton className="h-5 w-2/5" />
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} className="h-3 w-full" />
      ))}
    </div>
  );
}
