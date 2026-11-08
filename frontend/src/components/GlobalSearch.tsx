import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { Search, X } from 'lucide-react';
import { historyApi } from '../api/history';
import { resumeDocumentApi } from '../api/resumeDocument';
import { knowledgeBaseApi } from '../api/knowledgebase';
import { interviewApi } from '../api/interview';
import { ROUTES } from '../constants/routes';
import { EmptyState, Skeleton } from './ui';

type Hit = {
  key: string;
  group: string;
  title: string;
  subtitle: string;
  path: string;
};

const GROUP_ORDER = ['在线简历', '上传简历', '知识库', '面试记录'];

/**
 * 前端聚合的全局搜索：后端列表接口本来就返回全量数据，这里并行拉取后在浏览器里过滤，
 * 不新增后端端点、不改动分页。单个列表失败不影响其它分组（Promise.allSettled）。
 */
async function loadHits(): Promise<Hit[]> {
  const [resumes, documents, bases, sessions] = await Promise.allSettled([
    historyApi.getResumes(),
    resumeDocumentApi.list(),
    knowledgeBaseApi.getAllKnowledgeBases(),
    interviewApi.listSessions(),
  ]);

  const hits: Hit[] = [];
  if (documents.status === 'fulfilled') {
    for (const doc of documents.value) {
      hits.push({
        key: `doc-${doc.id}`, group: '在线简历', title: doc.title || '未命名简历',
        subtitle: doc.sourceResumeId !== null ? '来自上传简历' : '空白简历',
        path: ROUTES.resumeStudio(doc.id),
      });
    }
  }
  if (resumes.status === 'fulfilled') {
    for (const item of resumes.value) {
      hits.push({
        key: `resume-${item.id}`, group: '上传简历', title: item.filename,
        subtitle: `上传于 ${item.uploadedAt?.slice(0, 10) ?? '未知时间'}`,
        path: `/history/${item.id}`,
      });
    }
  }
  if (bases.status === 'fulfilled') {
    for (const kb of bases.value) {
      hits.push({
        key: `kb-${kb.id}`, group: '知识库', title: kb.name,
        subtitle: kb.originalFilename || kb.category || '',
        path: '/knowledgebase',
      });
    }
  }
  if (sessions.status === 'fulfilled') {
    for (const session of sessions.value) {
      hits.push({
        key: `session-${session.sessionId}`, group: '面试记录',
        title: `面试练习 · ${session.skillId || '通用'}`,
        subtitle: `创建于 ${session.createdAt?.slice(0, 10) ?? '未知时间'}`,
        path: `/interviews/${session.sessionId}`,
      });
    }
  }
  return hits;
}

export function GlobalSearch() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<Hit[]>([]);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setOpen((value) => !value);
      }
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    if (!open) return;
    setQuery('');
    setLoading(true);
    let active = true;
    loadHits().then((result) => {
      if (!active) return;
      setHits(result);
      setLoading(false);
    });
    window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => { active = false; };
  }, [open]);

  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    const matched = keyword
      ? hits.filter((hit) => `${hit.title} ${hit.subtitle} ${hit.group}`.toLowerCase().includes(keyword))
      : hits;
    // 空查询时每组只显示前 4 条，避免长列表淹没弹窗
    if (keyword) return matched.slice(0, 40);
    return GROUP_ORDER.flatMap((group) => matched.filter((hit) => hit.group === group).slice(0, 4));
  }, [hits, query]);

  const go = (hit: Hit) => {
    navigate(hit.path);
    setOpen(false);
  };

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="打开全局搜索"
        className="flex w-full items-center gap-2 rounded-lg bg-slate-100 px-3 py-2 text-slate-600 transition-colors hover:bg-slate-200 dark:bg-[rgb(var(--skin-surface)/0.5)] dark:text-slate-300 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.6)]"
      >
        <Search className="h-4 w-4 shrink-0" />
        <span className="flex-1 text-left text-sm">全局搜索</span>
        <kbd className="rounded border border-slate-300 px-1 text-[10px] text-slate-400 dark:border-slate-600">Ctrl K</kbd>
      </button>

      {open && typeof document !== 'undefined' && createPortal(
        <div
          className="fixed inset-0 z-[90] flex items-start justify-center bg-black/50 p-4 pt-[12vh] backdrop-blur-sm"
          onClick={() => setOpen(false)}
        >
          <div
            role="dialog"
            aria-label="全局搜索"
            className="w-full max-w-xl overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl dark:border-[rgb(var(--skin-accent)/0.25)] dark:bg-slate-900"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="flex items-center gap-3 border-b border-slate-100 px-4 py-3 dark:border-slate-700">
              <Search className="h-5 w-5 shrink-0 text-slate-400" />
              <input
                ref={inputRef}
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索简历、在线文档、知识库、面试记录…"
                className="flex-1 bg-transparent text-sm text-slate-800 outline-none placeholder:text-slate-400 dark:text-slate-100"
              />
              <button
                type="button"
                aria-label="关闭搜索"
                onClick={() => setOpen(false)}
                className="rounded p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="max-h-[60vh] overflow-y-auto p-2">
              {loading ? (
                <div className="space-y-2 p-2">
                  <Skeleton className="h-4 w-1/3" />
                  <Skeleton className="h-10 w-full" />
                  <Skeleton className="h-10 w-full" />
                </div>
              ) : filtered.length === 0 ? (
                <EmptyState compact title="没有匹配结果" description="换个关键词试试，或先上传一份简历 / 知识库" />
              ) : (
                GROUP_ORDER.map((group) => {
                  const items = filtered.filter((hit) => hit.group === group);
                  if (items.length === 0) return null;
                  return (
                    <div key={group} className="mb-2">
                      <p className="px-3 py-1 text-xs font-semibold uppercase tracking-wider text-slate-400">{group}</p>
                      {items.map((hit) => (
                        <button
                          key={hit.key}
                          type="button"
                          onClick={() => go(hit)}
                          className="flex w-full flex-col items-start gap-0.5 rounded-xl px-3 py-2 text-left transition-colors hover:bg-primary-50 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.6)]"
                        >
                          <span className="w-full truncate text-sm font-medium text-slate-800 dark:text-slate-100">{hit.title}</span>
                          <span className="w-full truncate text-xs text-slate-400">{hit.subtitle}</span>
                        </button>
                      ))}
                    </div>
                  );
                })
              )}
            </div>
            {!loading && (
              <p className="border-t border-slate-100 px-4 py-2 text-xs text-slate-400 dark:border-slate-700">
                共 {filtered.length} 条结果 · 点击跳转 · Esc 关闭
              </p>
            )}
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
