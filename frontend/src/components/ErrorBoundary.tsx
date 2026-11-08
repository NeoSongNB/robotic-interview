import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';

interface Props {
  children: ReactNode;
  /** 自定义兜底界面；不传则使用内置的重试卡片。 */
  fallback?: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * 捕获渲染期异常与 lazy chunk 加载失败。
 * 之前 20 个 lazy 页面共用一个 Suspense，chunk 加载失败会直接白屏。
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] 渲染失败', error, info.componentStack);
  }

  private handleReload = () => {
    this.setState({ error: null });
    window.location.reload();
  };

  render() {
    if (!this.state.error) return this.props.children;
    if (this.props.fallback) return this.props.fallback;

    return (
      <div
        role="alert"
        className="flex min-h-[50vh] flex-col items-center justify-center gap-4 px-6 text-center"
      >
        <div className="rounded-2xl bg-amber-100 p-3 text-amber-600 dark:bg-amber-900/30 dark:text-amber-300">
          <AlertTriangle className="h-6 w-6" />
        </div>
        <p className="text-base font-semibold text-slate-800 dark:text-slate-100">
          页面加载失败
        </p>
        <p className="max-w-md text-sm text-slate-500 dark:text-slate-400">
          可能是网络波动导致资源未加载完整。刷新页面通常即可恢复；若反复出现，请检查浏览器控制台。
        </p>
        <button
          type="button"
          onClick={this.handleReload}
          className="btn-primary mt-1 inline-flex items-center gap-2 rounded-xl px-5 py-2.5 text-sm"
        >
          <RefreshCw className="h-4 w-4" />
          重新加载
        </button>
      </div>
    );
  }
}
