import {Check, CircleAlert, X} from 'lucide-react';
import {
  describeOperation,
  hasDeleteOperation,
  type ResumeEditProposal as Proposal,
} from '../../types/resumeDocument';

interface ResumeEditProposalProps {
  proposal: Proposal;
  applying: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/** 修改提案确认卡片。确认前不写入正式简历，取消后数据库不发生任何变化。 */
export default function ResumeEditProposal({
  proposal, applying, onConfirm, onCancel,
}: ResumeEditProposalProps) {
  const needsClarification = proposal.needsClarification;
  const deleting = hasDeleteOperation(proposal);
  // 删除、合并或调整展示优先级都涉及原文取舍，必须让用户核对原文与理由后再保存。
  const needsReview = proposal.operations.some(operation =>
    operation.op === 'DELETE' || operation.op === 'REORDER'
    || (operation.op === 'UPDATE'
      && (operation.target === 'INTERNSHIP_HIGHLIGHT' || operation.target === 'HIGHLIGHT')));

  return (
    <div className="rounded-xl border border-primary-500/35 bg-white/90 dark:bg-[rgb(var(--skin-surface-deep)/0.7)] p-4 shadow-lg dark:shadow-[0_0_28px_-14px_rgb(var(--skin-accent)/0.8)]">
      <div className="flex items-start justify-between gap-2 mb-2">
        <h3 className="text-sm font-semibold text-slate-800 dark:text-white">
          {needsClarification ? '需要补充信息' : 'AI 修改提案（待确认）'}
        </h3>
        <button
          onClick={onCancel}
          disabled={applying}
          className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 rounded disabled:opacity-50"
          title="取消修改"
        >
          <X className="w-4 h-4"/>
        </button>
      </div>

      <p className="text-sm text-slate-600 dark:text-slate-300 whitespace-pre-wrap mb-3">
        {needsClarification
          ? proposal.clarificationQuestion || proposal.reply
          : proposal.reply}
      </p>

      {!needsClarification && (
        <>
          {proposal.changeSummary.length > 0 && (
            <div className="mb-3">
              <div className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">本次修改</div>
              <ul className="space-y-1">
                {proposal.changeSummary.map((summary, index) => (
                  <li key={`${summary}-${index}`}
                      className="flex gap-2 text-sm text-slate-700 dark:text-slate-200">
                    <Check className="w-3.5 h-3.5 mt-1 flex-none text-emerald-500"/>
                    <span>{summary}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {proposal.operations.length > 0 && (
            <details className="mb-3 text-xs text-slate-500 dark:text-slate-400">
              <summary className="cursor-pointer select-none">查看修改明细（{proposal.operations.length} 项）</summary>
              <ul className="mt-2 space-y-1 pl-4 list-disc">
                {proposal.operations.map((operation, index) => (
                  <li key={`${operation.op}-${operation.target}-${index}`}>
                    {describeOperation(operation)}
                  </li>
                ))}
              </ul>
            </details>
          )}

          {needsReview && (
            <div className={`flex items-start gap-2 mb-3 rounded-lg px-3 py-2 text-xs ${
              deleting
                ? 'bg-red-50 dark:bg-red-500/10 text-red-700 dark:text-red-300'
                : 'bg-amber-50 dark:bg-amber-500/10 text-amber-700 dark:text-amber-300'
            }`}>
              <CircleAlert className="w-4 h-4 flex-none mt-0.5"/>
              <span>
                本次提案涉及删除、合并或调整展示优先级，请在上方「本次修改」中核对涉及的原文与理由后再保存；
                每条重点经历应能回答：你具体做了什么、为什么这么做、怎么证明有效。
              </span>
            </div>
          )}

          <div className="flex gap-2 justify-end">
            <button
              onClick={onCancel}
              disabled={applying}
              className="btn-secondary px-4 py-2 rounded-lg text-sm disabled:opacity-50"
            >
              取消修改
            </button>
            <button
              onClick={onConfirm}
              disabled={applying}
              className="btn-primary px-4 py-2 rounded-lg text-sm disabled:opacity-50"
            >
              {applying ? '保存中...' : '确认修改'}
            </button>
          </div>
        </>
      )}

      {needsClarification && (
        <div className="flex justify-end">
          <button onClick={onCancel} className="btn-secondary px-4 py-2 rounded-lg text-sm">
            我知道了
          </button>
        </div>
      )}
    </div>
  );
}
