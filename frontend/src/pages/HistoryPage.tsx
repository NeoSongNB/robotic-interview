import { toast } from '../components/ui/Toast';
import {useCallback, useEffect, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {
  AlertCircle,
  CheckCircle,
  Clock,
  FilePlus2,
  FileStack,
  FileText,
  PencilLine,
  Printer,
  RefreshCw,
  Sparkles,
  Trash2,
  Upload,
} from 'lucide-react';
import {historyApi, ResumeListItem} from '../api/history';
import {resumeDocumentApi} from '../api/resumeDocument';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import {formatDateOnly, formatDateTime} from '../utils/date';
import {getScoreProgressColor} from '../utils/score';
import type {ResumeDocument} from '../types/resumeDocument';
import { ROUTES } from '../constants/routes';

interface HistoryListProps {
  onSelectResume: (id: number) => void;
}

function isAnalyzing(status?: string): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

function AnalyzeStatusIcon({status}: { status?: string }) {
  if (status === 'FAILED') return <AlertCircle className="w-4 h-4 text-red-500 dark:text-red-400"/>;
  if (isAnalyzing(status)) return <RefreshCw className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin"/>;
  if (status === 'COMPLETED') return <CheckCircle className="w-4 h-4 text-green-500 dark:text-green-400"/>;
  return <Clock className="w-4 h-4 text-yellow-500 dark:text-yellow-400"/>;
}

function getAnalyzeStatusText(status?: string): string {
  if (status === 'FAILED') return '分析失败';
  if (status === 'PROCESSING') return '分析中';
  if (status === 'PENDING') return '等待分析';
  if (status === 'COMPLETED') return '分析完成';
  return '待分析';
}

function resumesEqual(a: ResumeListItem[], b: ResumeListItem[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    if (a[i].id !== b[i].id ||
        a[i].analyzeStatus !== b[i].analyzeStatus ||
        a[i].latestScore !== b[i].latestScore) return false;
  }
  return true;
}

export default function HistoryList({onSelectResume}: HistoryListProps) {
  const navigate = useNavigate();
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ id: number; filename: string } | null>(null);
  // 原始简历 id -> 在线编辑文档 id，用于判断哪些简历已有可编辑数据
  const [documentBySource, setDocumentBySource] = useState<Map<number, number>>(new Map());
  // 全部在线简历（含空白简历），空白简历没有原始简历行，必须单独列出才能再次进入
  const [documents, setDocuments] = useState<ResumeDocument[]>([]);
  const [creatingBlank, setCreatingBlank] = useState(false);
  const [managingId, setManagingId] = useState<number | null>(null);
  const [deletingDocumentId, setDeletingDocumentId] = useState<number | null>(null);
  const [documentDeleteConfirm, setDocumentDeleteConfirm] =
    useState<{ id: number; title: string } | null>(null);

  const loadResumes = useCallback(async (isPolling = false) => {
    if (!isPolling) setLoading(true);
    try {
      const data = await historyApi.getResumes();
      setResumes(prev => {
        if (isPolling && resumesEqual(prev, data)) return prev;
        return data;
      });
    } catch (err) {
      console.error('加载历史记录失败', err);
    } finally {
      if (!isPolling) setLoading(false);
    }
  }, []);

  // 在线简历列表单独加载，失败不影响原有的上传 / 历史 / 分析功能
  const loadDocuments = useCallback(async () => {
    try {
      const documents = await resumeDocumentApi.list();
      const mapping = new Map<number, number>();
      for (const document of documents) {
        if (document.sourceResumeId !== null) {
          mapping.set(document.sourceResumeId, document.id);
        }
      }
      setDocumentBySource(mapping);
      setDocuments(documents);
    } catch (err) {
      console.error('加载在线简历列表失败', err);
    }
  }, []);

  useEffect(() => {
    loadResumes();
    loadDocuments();
  }, [loadResumes, loadDocuments]);

  // 轮询：有分析中的简历时启动 3s 轮询
  const hasAnalyzing = resumes.some(r => isAnalyzing(r.analyzeStatus));

  useEffect(() => {
    if (!hasAnalyzing) return;
    const id = window.setInterval(() => loadResumes(true), 3000);
    return () => clearInterval(id);
  }, [hasAnalyzing, loadResumes]);

  const handleCreateBlank = async () => {
    if (creatingBlank) return;
    setCreatingBlank(true);
    try {
      const document = await resumeDocumentApi.createBlank();
      navigate(ROUTES.resumeStudio(document.id));
    } catch (err) {
      toast('error', err instanceof Error ? err.message : '新建简历失败，请稍后重试');
    } finally {
      setCreatingBlank(false);
    }
  };

  const handleManage = async (resumeId: number, filename: string) => {
    const existing = documentBySource.get(resumeId);
    if (existing) {
      navigate(ROUTES.resumeStudio(existing));
      return;
    }
    setManagingId(resumeId);
    try {
      const document = await resumeDocumentApi.initFromResume(resumeId, filename);
      setDocumentBySource(prev => new Map(prev).set(resumeId, document.id));
      navigate(ROUTES.resumeStudio(document.id));
    } catch (err) {
      toast('error', err instanceof Error ? err.message : '打开编辑工作台失败，请稍后重试');
    } finally {
      setManagingId(null);
    }
  };

  const handleExportPdf = (resumeId: number) => {
    const documentId = documentBySource.get(resumeId);
    if (!documentId) return;
    navigate(ROUTES.resumeStudio(documentId), {state: {autoPrint: true}});
  };

  const handleDeleteClick = (id: number, filename: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteConfirm({id, filename});
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConfirm) return;

    const {id} = deleteConfirm;
    setDeletingId(id);
    try {
      await historyApi.deleteResume(id);
      await loadResumes();
      setDeleteConfirm(null);
    } catch (err) {
      toast('error', err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingId(null);
    }
  };

  const handleDeleteDocumentConfirm = async () => {
    if (!documentDeleteConfirm) return;
    const {id} = documentDeleteConfirm;
    setDeletingDocumentId(id);
    try {
      await resumeDocumentApi.remove(id);
      setDocumentDeleteConfirm(null);
      await loadDocuments();
    } catch (err) {
      toast('error', err instanceof Error ? err.message : '删除在线简历失败，请稍后重试');
    } finally {
      setDeletingDocumentId(null);
    }
  };

  const filteredResumes = resumes.filter(resume =>
    resume.filename.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <motion.div
      className="w-full"
      initial={{opacity: 0}}
      animate={{opacity: 1}}
    >
      {/* 头部 */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
            <FileStack className="w-7 h-7 text-primary-500 dark:drop-shadow-[0_0_10px_rgb(var(--skin-accent)/0.8)]" />
            简历管理
          </h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1">管理您的简历，AI 智能分析与评分</p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={() => navigate(ROUTES.resumeUpload)}
            className="btn-primary flex items-center gap-2 px-4 py-2 rounded-lg"
          >
            <Upload className="w-4 h-4" />
            上传简历
          </button>
          <button
            onClick={handleCreateBlank}
            disabled={creatingBlank}
            className="btn-secondary flex items-center gap-2 px-4 py-2 rounded-lg disabled:opacity-50"
            title="无需上传文件，直接创建一份可在线编辑的简历"
          >
            <FilePlus2 className="w-4 h-4" />
            新建空白简历
          </button>
          <button
            onClick={() => navigate('/interview-hub')}
            className="btn-secondary flex items-center gap-2 px-4 py-2 rounded-lg"
          >
            <Sparkles className="w-4 h-4" />
            模拟面试
          </button>
        </div>
      </div>

      {/* 搜索栏 */}
      <div className="mb-6">
        <div className="flex items-center gap-3 bg-white/85 dark:bg-[rgb(var(--skin-surface-deep)/0.6)] backdrop-blur-md border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.2)] rounded-xl px-4 py-3 max-w-md focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 dark:focus-within:border-neon-purple/70 dark:focus-within:ring-neon-purple/20 dark:focus-within:shadow-[0_0_0_1px_rgb(var(--skin-accent)/0.4),0_0_24px_-8px_rgb(var(--skin-accent)/0.6)] transition-all">
          <svg className="w-5 h-5 text-slate-400" viewBox="0 0 24 24" fill="none">
            <circle cx="11" cy="11" r="8" stroke="currentColor" strokeWidth="2"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
          </svg>
          <input
            type="text"
            placeholder="搜索简历..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="flex-1 outline-none text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent"
          />
        </div>
      </div>

      {/* 加载状态 */}
      {loading && (
        <div className="text-center py-20">
          <motion.div
            className="w-10 h-10 border-3 border-slate-200 dark:text-slate-200 border-t-primary-500 rounded-full mx-auto mb-4"
            animate={{rotate: 360}}
            transition={{duration: 1, repeat: Infinity, ease: "linear"}}
          />
          <p className="text-slate-500 dark:text-slate-400">加载中...</p>
        </div>
      )}

      {/* 空状态 */}
      {!loading && filteredResumes.length === 0 && documents.length === 0 && (
        <motion.div
          className="dark-card text-center py-20"
          initial={{opacity: 0, scale: 0.95}}
          animate={{opacity: 1, scale: 1}}
        >
          <div className="text-6xl mb-6">📄</div>
          <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">暂无简历记录</h3>
          <p className="text-slate-500 dark:text-slate-400">上传简历开始您的第一次 AI 面试分析，或直接新建一份在线简历</p>
          <button
            onClick={handleCreateBlank}
            disabled={creatingBlank}
            className="btn-secondary mt-5 inline-flex items-center gap-2 px-4 py-2 rounded-lg disabled:opacity-50"
          >
            <FilePlus2 className="w-4 h-4"/>新建空白简历
          </button>
        </motion.div>
      )}

      {/* 在线简历：空白简历在上传列表里没有对应行，必须在这里单独列出才能再次进入 */}
      {!loading && documents.length > 0 && (
        <motion.div
          className="dark-card overflow-hidden mb-6"
          initial={{opacity: 0, y: 12}}
          animate={{opacity: 1, y: 0}}
        >
          <div className="px-6 py-4 flex items-center justify-between gap-3 border-b border-slate-100 dark:border-[rgb(var(--skin-accent)/0.14)]">
            <div>
              <h2 className="font-semibold text-slate-800 dark:text-white flex items-center gap-2">
                <FileText className="w-4 h-4 text-primary-500"/>
                在线简历
              </h2>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                在工作台继续编辑、AI 修改并导出 PDF
              </p>
            </div>
            <span className="text-xs text-slate-500 dark:text-slate-400">共 {documents.length} 份</span>
          </div>
          <ul>
            {documents.map(document => (
              <li
                key={document.id}
                className="px-6 py-4 flex items-center gap-3 border-b border-slate-100 dark:border-[rgb(var(--skin-accent)/0.1)] last:border-0 hover:bg-slate-50 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.45)] transition-colors"
              >
                <button
                  type="button"
                  onClick={() => navigate(ROUTES.resumeStudio(document.id))}
                  className="flex-1 min-w-0 text-left"
                >
                  <div className="flex items-center gap-2 flex-wrap">
                    <span
                      className="font-medium text-slate-800 dark:text-white truncate">{document.title}</span>
                    <span
                      className="text-[11px] px-2 py-0.5 rounded-full bg-primary-50 text-primary-600 dark:bg-[rgb(var(--skin-accent)/0.16)] dark:text-neon-cyan">
                      {document.sourceResumeId !== null ? '来自上传简历' : '空白简历'}
                    </span>
                  </div>
                  <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                    最近保存：{formatDateTime(document.updatedAt)}
                  </p>
                </button>
                <div className="flex items-center gap-1 flex-none">
                  <button
                    type="button"
                    onClick={() => navigate(ROUTES.resumeStudio(document.id))}
                    className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-500/15 rounded-lg transition-colors"
                    title="继续编辑"
                  >
                    <PencilLine className="w-4 h-4"/>
                  </button>
                  <button
                    type="button"
                    onClick={() => navigate(ROUTES.resumeStudio(document.id), {state: {autoPrint: true}})}
                    className="p-2 text-slate-400 hover:text-emerald-500 hover:bg-emerald-50 dark:hover:bg-emerald-500/15 rounded-lg transition-colors"
                    title="导出 PDF"
                  >
                    <Printer className="w-4 h-4"/>
                  </button>
                  <button
                    type="button"
                    onClick={() => setDocumentDeleteConfirm({id: document.id, title: document.title})}
                    disabled={deletingDocumentId === document.id}
                    className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50"
                    title="删除在线简历"
                  >
                    {deletingDocumentId === document.id ? (
                      <RefreshCw className="w-4 h-4 animate-spin"/>
                    ) : (
                      <Trash2 className="w-4 h-4"/>
                    )}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </motion.div>
      )}

      {/* 表格 */}
      {!loading && filteredResumes.length > 0 && (
        <motion.div
          className="dark-card overflow-hidden"
          initial={{opacity: 0, y: 20}}
          animate={{opacity: 1, y: 0}}
          transition={{delay: 0.2}}
        >
          <table className="w-full">
            <thead>
            <tr className="bg-slate-50 dark:bg-[rgb(var(--skin-surface-deep)/0.7)] border-b border-slate-100 dark:border-[rgb(var(--skin-accent)/0.14)]">
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">简历名称</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">上传日期</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">分析状态</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">AI 评分</th>
              <th className="text-left px-6 py-4 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">面试状态</th>
              <th className="w-44"></th>
            </tr>
            </thead>
            <tbody>
            <AnimatePresence>
              {filteredResumes.map((resume, index) => (
                <motion.tr
                  key={resume.id}
                  initial={{opacity: 0, x: -20}}
                  animate={{opacity: 1, x: 0}}
                  transition={{delay: index * 0.05}}
                  onClick={() => onSelectResume(resume.id)}
                  className="border-b border-slate-100 dark:border-[rgb(var(--skin-accent)/0.1)] last:border-0 hover:bg-slate-50 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.45)] dark:hover:shadow-[inset_0_0_32px_-16px_rgb(var(--skin-accent)/0.45)] cursor-pointer transition-all duration-300 group"
                >
                  <td className="px-6 py-5">
                    <div className="flex items-center gap-4">
                      <div
                        className="w-10 h-10 bg-primary-50 dark:bg-gradient-to-br dark:from-neon-purple/30 dark:to-neon-blue/25 dark:shadow-[0_0_16px_-4px_rgb(var(--skin-accent)/0.8)] rounded-xl flex items-center justify-center text-primary-500 dark:text-neon-cyan transition-transform duration-300 group-hover:scale-110 group-hover:rotate-3">
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
                          <path d="M14 2H6C5.46957 2 4.96086 2.21071 4.58579 2.58579C4.21071 2.96086 4 3.46957 4 4V20C4 20.5304 4.21071 21.0391 4.58579 21.4142C4.96086 21.7893 5.46957 22 6 22H18C18.5304 22 19.0391 21.7893 19.4142 21.4142C19.7893 21.0391 20 20.5304 20 20V8L14 2Z"
                                stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                                strokeLinejoin="round"/>
                          <polyline points="14,2 14,8 20,8" stroke="currentColor" strokeWidth="2"
                                    strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                      </div>
                      <span className="font-medium text-slate-800 dark:text-white">{resume.filename}</span>
                    </div>
                  </td>
                  <td className="px-6 py-5 text-slate-500 dark:text-slate-400">{formatDateOnly(resume.uploadedAt)}</td>
                  <td className="px-6 py-5">
                    <div className="flex items-center gap-2">
                      <AnalyzeStatusIcon status={resume.analyzeStatus}/>
                      <span className="text-sm text-slate-600 dark:text-slate-300">
                        {getAnalyzeStatusText(resume.analyzeStatus)}
                      </span>
                    </div>
                  </td>
                  <td className="px-6 py-5">
                    {resume.analyzeStatus === 'COMPLETED' && resume.latestScore !== undefined ? (
                      <div className="flex items-center gap-3">
                        <div
                          className="w-20 h-2 bg-slate-100 dark:bg-[rgb(var(--skin-surface-deep)/0.9)] rounded-full overflow-hidden dark:shadow-[inset_0_0_6px_rgb(var(--skin-shadow)/0.9)]">
                          <motion.div
                            className={`h-full ${getScoreProgressColor(resume.latestScore)} dark:neon-progress rounded-full dark:shadow-[0_0_10px_rgb(var(--skin-accent-2)/0.8)]`}
                            initial={{width: 0}}
                            animate={{width: `${resume.latestScore}%`}}
                            transition={{duration: 0.8, delay: index * 0.05}}
                          />
                        </div>
                        <span className="font-bold text-slate-800 dark:text-white">{resume.latestScore}</span>
                      </div>
                    ) : isAnalyzing(resume.analyzeStatus) ? (
                      <span className="text-blue-500 dark:text-blue-400 text-sm">生成中...</span>
                    ) : resume.analyzeStatus === 'FAILED' ? (
                      <span className="text-red-500 dark:text-red-400 text-sm"
                            title={resume.analyzeError}>失败</span>
                    ) : (
                      <span className="text-slate-400 dark:text-slate-500">-</span>
                    )}
                  </td>
                  <td className="px-6 py-5">
                    {resume.interviewCount > 0 ? (
                      <span
                        className="inline-flex items-center gap-1.5 px-3 py-1 bg-emerald-50 dark:bg-glow-success/15 dark:border dark:border-glow-success/30 dark:text-glow-success dark:shadow-[0_0_12px_-4px_rgba(0,214,160,0.6)] text-emerald-600 rounded-full text-sm font-medium">
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none">
                          <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2"/>
                          <polyline points="9,12 11,14 15,10" stroke="currentColor" strokeWidth="2"
                                    strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                        已完成
                      </span>
                    ) : (
                      <span
                        className="inline-flex px-3 py-1 bg-slate-100 dark:bg-[rgb(var(--skin-surface)/0.6)] dark:border dark:border-[rgb(var(--skin-accent)/0.2)] text-slate-500 dark:text-slate-300 rounded-full text-sm">待面试</span>
                    )}
                  </td>
                  <td className="px-4">
                    <div className="flex items-center gap-1">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          void handleManage(resume.id, resume.filename);
                        }}
                        disabled={managingId === resume.id}
                        className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-500/15 rounded-lg transition-colors disabled:opacity-50"
                        title="管理简历（在线编辑）"
                      >
                        {managingId === resume.id ? (
                          <RefreshCw className="w-4 h-4 animate-spin"/>
                        ) : (
                          <PencilLine className="w-4 h-4"/>
                        )}
                      </button>
                      {documentBySource.has(resume.id) && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            handleExportPdf(resume.id);
                          }}
                          className="p-2 text-slate-400 hover:text-emerald-500 hover:bg-emerald-50 dark:hover:bg-emerald-500/15 rounded-lg transition-colors"
                          title="导出 PDF（在线简历）"
                        >
                          <Printer className="w-4 h-4"/>
                        </button>
                      )}
                      <button
                        onClick={(e) => handleDeleteClick(resume.id, resume.filename, e)}
                        disabled={deletingId === resume.id}
                        className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        title="删除简历"
                      >
                        {deletingId === resume.id ? (
                          <motion.div
                            className="w-5 h-5 border-2 border-red-500 border-t-transparent rounded-full"
                            animate={{rotate: 360}}
                            transition={{duration: 1, repeat: Infinity, ease: "linear"}}
                          />
                        ) : (
                          <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
                            <path d="M3 6H5H21M8 6V4C8 3.46957 8.21071 2.96086 8.58579 2.58579C8.96086 2.21071 9.46957 2 10 2H14C14.5304 2 15.0391 2.21071 15.4142 2.58579C15.7893 2.96086 16 3.46957 16 4V6M19 6V20C19 20.5304 18.7893 21.0391 18.4142 21.4142C18.0391 21.7893 17.5304 22 17 22H7C6.46957 22 5.96086 21.7893 5.58579 21.4142C5.21071 21.0391 5 20.5304 5 20V6H19Z"
                                  stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                                  strokeLinejoin="round"/>
                            <path d="M10 11V17M14 11V17" stroke="currentColor" strokeWidth="2"
                                  strokeLinecap="round" strokeLinejoin="round"/>
                          </svg>
                        )}
                      </button>
                      <svg
                        className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-1 transition-all"
                        viewBox="0 0 24 24" fill="none">
                        <polyline points="9,18 15,12 9,6" stroke="currentColor" strokeWidth="2"
                                  strokeLinecap="round" strokeLinejoin="round"/>
                      </svg>
                    </div>
                  </td>
                </motion.tr>
              ))}
            </AnimatePresence>
            </tbody>
          </table>
        </motion.div>
      )}

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteConfirm !== null}
        item={deleteConfirm}
        itemType="简历"
        loading={deletingId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteConfirm(null)}
        customMessage={
          deleteConfirm ? (
            <>
              <p className="mb-2">确定要删除简历 <strong>"{deleteConfirm.filename}"</strong> 吗？</p>
              <p className="text-sm text-slate-500 dark:text-slate-400 mb-2">删除后将同时删除：</p>
              <ul className="text-sm text-slate-500 dark:text-red-400 list-disc list-inside mb-2">
                <li>简历评价记录</li>
                <li>所有模拟面试记录</li>
              </ul>
              <p className="text-sm font-semibold text-red-600">此操作不可恢复！</p>
            </>
          ) : undefined
        }
      />

      {/* 在线简历删除确认 */}
      <DeleteConfirmDialog
        open={documentDeleteConfirm !== null}
        item={documentDeleteConfirm}
        itemType="在线简历"
        loading={deletingDocumentId !== null}
        onConfirm={handleDeleteDocumentConfirm}
        onCancel={() => setDocumentDeleteConfirm(null)}
        customMessage={
          documentDeleteConfirm ? (
            <>
              <p className="mb-2">确定要删除在线简历 <strong>"{documentDeleteConfirm.title}"</strong> 吗？</p>
              <p className="text-sm text-slate-500 dark:text-slate-400">
                仅删除这份在线编辑版本，原始上传简历与 AI 分析结果不受影响。
              </p>
              <p className="text-sm font-semibold text-red-600 mt-2">此操作不可恢复！</p>
            </>
          ) : undefined
        }
      />
    </motion.div>
  );
}
