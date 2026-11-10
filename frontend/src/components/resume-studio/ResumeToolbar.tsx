import {ArrowLeft, Check, CircleAlert, Loader2, Download, Save} from 'lucide-react';
import type {SaveStatus} from '../../types/resumeDocument';

interface ResumeToolbarProps {
  title: string;
  onTitleChange: (title: string) => void;
  saveStatus: SaveStatus;
  onSave: () => void;
  templateCode: string;
  onBack: () => void;
  onExportPdf: () => void;
  exportingPdf: boolean;
  busy: boolean;
  /** 有待确认的 AI 提案时冻结输入，避免自动保存与提案版本冲突。 */
  frozen?: boolean;
}

function StatusBadge({status}: {status: SaveStatus}) {
  if (status === 'saving') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-blue-600 dark:text-blue-300">
        <Loader2 className="w-3.5 h-3.5 animate-spin"/>保存中
      </span>
    );
  }
  if (status === 'error') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs text-red-600 dark:text-red-300">
        <CircleAlert className="w-3.5 h-3.5"/>保存失败
      </span>
    );
  }
  if (status === 'idle') {
    return <span className="text-xs text-amber-600 dark:text-amber-300">有未保存的修改</span>;
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-emerald-600 dark:text-emerald-300">
      <Check className="w-3.5 h-3.5"/>已保存
    </span>
  );
}

/** 工作台顶部工具栏：返回、名称、保存状态、模板（预留扩展）、导出 PDF。 */
export default function ResumeToolbar({
  title, onTitleChange, saveStatus, onSave, templateCode, onBack, onExportPdf, exportingPdf, busy,
  frozen = false,
}: ResumeToolbarProps) {
  return (
    <div className="flex items-center gap-3 flex-wrap pb-4 border-b border-slate-200 dark:border-[rgb(var(--skin-accent)/0.16)]">
      <button
        onClick={onBack}
        className="btn-secondary inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm"
        title="返回简历管理"
      >
        <ArrowLeft className="w-4 h-4"/>返回
      </button>

      <input
        value={title}
        onChange={event => onTitleChange(event.target.value)}
        maxLength={200}
        disabled={frozen}
        aria-label="简历名称"
        className="dark-input min-w-[180px] max-w-[280px] flex-1 px-3 py-2 rounded-lg text-sm font-semibold disabled:opacity-60"
        placeholder="简历名称"
      />

      <div className="min-w-[92px]"><StatusBadge status={saveStatus}/></div>

      <button
        onClick={onSave}
        disabled={busy || saveStatus === 'saving'}
        className="btn-secondary inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm disabled:opacity-50"
        title="保存当前简历（Ctrl / Cmd + S）"
      >
        <Save className="w-4 h-4"/>保存
      </button>

      <select
        value={templateCode}
        disabled
        aria-label="简历模板"
        className="dark-input px-3 py-2 rounded-lg text-sm opacity-80 cursor-not-allowed"
        title="第一版仅提供默认模板，后续版本将支持更多模板"
      >
        <option value="default">默认模板 · A4</option>
      </select>

      <button
        onClick={onExportPdf}
        disabled={exportingPdf}
        className="btn-primary inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm disabled:opacity-50"
        title="下载可选中、可搜索的 PDF"
      >
        {exportingPdf ? <Loader2 className="w-4 h-4 animate-spin"/> : <Download className="w-4 h-4"/>}
        {exportingPdf ? '导出中…' : '导出 PDF'}
      </button>
    </div>
  );
}
