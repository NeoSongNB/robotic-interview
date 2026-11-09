import { toast } from '../components/ui/Toast';
import { Button, Card, EmptyState } from '../components/ui';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {historyApi} from '../api/history';
import {interviewApi, type TextSessionMeta} from '../api/interview';
import {voiceInterviewApi, SessionMeta} from '../api/voiceInterview';
import {formatDate} from '../utils/date';
import {getScoreProgressColor} from '../utils/score';
import {
  calculateInterviewStats,
  isCompletedStatus,
  isEvaluateCompleted,
} from './interviewHistoryStats.ts';
import {
  getVoiceEvaluationPresentation,
  shouldRefreshVoiceEvaluationPresentation,
} from './voiceEvaluationStatus.ts';
import {skillApi, type SkillDTO} from '../api/skill';
import {getTemplateName} from '../utils/voiceInterview';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import {
  AlertCircle,
  CheckCircle,
  ChevronRight,
  Clock,
  Download,
  FileText,
  Loader2,
  Mic,
  PlayCircle,
  RefreshCw,
  RotateCcw,
  Search,
  Trash2,
  TrendingUp,
  Users,
} from 'lucide-react';

type InterviewType = 'all' | 'text' | 'voice';

interface UnifiedInterviewItem {
  id: string;
  type: 'text' | 'voice';
  title: string;
  sessionId: string;
  status: string;
  evaluateStatus?: string;
  evaluateError?: string;
  evaluateStatusUpdatedAt?: string;
  overallScore: number | null;
  totalQuestions?: number;
  actualDuration?: number;
  createdAt: string;
  resumeId?: number;
  voiceSessionId?: number;
}

function isLiveStatus(status: string): boolean {
  return status === 'IN_PROGRESS' || status === 'PAUSED';
}

function isEvaluating(item: UnifiedInterviewItem): boolean {
  return item.evaluateStatus === 'PENDING' || item.evaluateStatus === 'PROCESSING';
}

function isEvaluateFailed(item: UnifiedInterviewItem): boolean {
  return item.evaluateStatus === 'FAILED';
}

function getItemEvaluationPresentation(item: UnifiedInterviewItem) {
  return getVoiceEvaluationPresentation({
    status: item.evaluateStatus,
    statusUpdatedAt: item.evaluateStatusUpdatedAt,
  });
}

function isVoiceEvaluationRetryable(item: UnifiedInterviewItem): boolean {
  return item.type === 'voice' && getItemEvaluationPresentation(item).retryable;
}

function StatusIcon({ item }: { item: UnifiedInterviewItem }) {
  if (isEvaluateFailed(item)) return <AlertCircle className="w-4 h-4 text-red-500 dark:text-red-400"/>;
  if (isVoiceEvaluationRetryable(item)) {
    return <AlertCircle className="w-4 h-4 text-amber-500 dark:text-amber-400"/>;
  }
  if (isEvaluating(item)) return <RefreshCw className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin"/>;
  if (isEvaluateCompleted(item)) return <CheckCircle className="w-4 h-4 text-green-500 dark:text-green-400"/>;
  if (item.status === 'IN_PROGRESS') return <PlayCircle className="w-4 h-4 text-blue-500 dark:text-blue-400"/>;
  return <Clock className="w-4 h-4 text-yellow-500 dark:text-yellow-400"/>;
}

function getStatusText(item: UnifiedInterviewItem): string {
  if (isEvaluateFailed(item)) return '评估失败';
  if (item.type === 'voice' && item.evaluateStatus) {
    return getItemEvaluationPresentation(item).label;
  }
  if (isEvaluating(item)) return item.evaluateStatus === 'PROCESSING' ? '评估中' : '等待评估';
  if (isEvaluateCompleted(item)) return '已完成';
  if (item.status === 'IN_PROGRESS') return '进行中';
  if (item.status === 'PAUSED') return '已暂停';
  if (isCompletedStatus(item.status)) return '已提交';
  return '已创建';
}

function formatDuration(seconds?: number): string {
  if (!seconds) return '-';
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}分${secs}秒`;
}

function StatCard({
  icon: Icon,
  label,
  value,
  suffix,
  color,
  glow,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number | string;
  suffix?: string;
  color: string;
  glow: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="dark-card neon-border-flow p-6"
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color} dark:shadow-[0_0_20px_-4px_rgb(var(--skin-accent)/0.8)] ${glow}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
          <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
          <p className="text-2xl font-bold text-slate-800 dark:text-white dark:drop-shadow-[0_0_10px_rgb(var(--skin-accent)/0.35)]">
            {value}{suffix && <span className="text-base font-normal text-slate-400 dark:text-slate-500 ml-1">{suffix}</span>}
          </p>
        </div>
      </div>
    </motion.div>
  );
}

function TypeBadge({ item }: { item: UnifiedInterviewItem }) {
  if (item.type === 'voice') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400 rounded-full text-xs font-medium">
        <Mic className="w-3 h-3" />
        语音
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded-full text-xs font-medium">
      <FileText className="w-3 h-3" />
      文字
    </span>
  );
}

interface InterviewHistoryPageProps {
  onBack: () => void;
  onViewInterview: (sessionId: string, resumeId?: number) => void;
  onRestartInterview?: (resumeId: number) => void;
  onContinueInterview?: (sessionId: string) => void;
}

/** Shallow comparison for polling change-detection */
function itemsEqual(a: UnifiedInterviewItem[], b: UnifiedInterviewItem[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    const ai = a[i], bi = b[i];
    if (ai.id !== bi.id || ai.status !== bi.status ||
        ai.evaluateStatus !== bi.evaluateStatus
        || ai.evaluateStatusUpdatedAt !== bi.evaluateStatusUpdatedAt
        || ai.overallScore !== bi.overallScore) return false;
  }
  return true;
}

export default function InterviewHistoryPage({
  onBack: _onBack,
  onViewInterview,
  onRestartInterview,
  onContinueInterview,
}: InterviewHistoryPageProps) {
  const navigate = useNavigate();
  const [items, setItems] = useState<UnifiedInterviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [typeFilter, setTypeFilter] = useState<InterviewType>('all');
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null);
  const [deleteItem, setDeleteItem] = useState<UnifiedInterviewItem | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);
  const [retryingVoiceSessionId, setRetryingVoiceSessionId] = useState<number | null>(null);
  const pollingRef = useRef<number | null>(null);
  const skillsRef = useRef<SkillDTO[]>([]);
  const skillsLoadedRef = useRef(false);

  const loadAll = useCallback(async (isPolling = false) => {
    if (!isPolling) setLoading(true);

    try {
      // Only fetch skills on first load; reuse cached ref on polling
      if (!skillsLoadedRef.current) {
        skillsRef.current = await skillApi.listSkills().catch(() => [] as SkillDTO[]);
        skillsLoadedRef.current = true;
      }
      const loadedSkills = skillsRef.current;
      const textInterviews = await loadTextInterviews(loadedSkills);
      const voiceSessions = await loadVoiceInterviews();

      const voiceWithNames = voiceSessions.map(item => {
        const skillName = getTemplateName(item.title, loadedSkills);
        return skillName !== item.title ? { ...item, title: skillName } : item;
      });

      const all = [...textInterviews, ...voiceWithNames];
      all.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

      setItems(prev => {
        const hasActiveEvaluation = all.some(item =>
          shouldRefreshVoiceEvaluationPresentation(item.evaluateStatus));
        if (isPolling && itemsEqual(prev, all) && !hasActiveEvaluation) return prev;
        return all;
      });
    } catch (err) {
      console.error('加载面试记录失败', err);
    } finally {
      if (!isPolling) setLoading(false);
    }
  }, []);

  // Load text interviews from dedicated API
  async function loadTextInterviews(skills: SkillDTO[]): Promise<UnifiedInterviewItem[]> {
    try {
      const sessions = await interviewApi.listSessions();
      return sessions
        .filter((session: TextSessionMeta) => session.sourceType !== 'KNOWLEDGE_BASE')
        .map((session: TextSessionMeta) => ({
          id: session.sessionId,
          type: 'text' as const,
          title: getTemplateName(session.skillId, skills),
          sessionId: session.sessionId,
          status: session.status,
          evaluateStatus: session.evaluateStatus ?? undefined,
          evaluateError: session.evaluateError ?? undefined,
          overallScore: session.overallScore,
          totalQuestions: session.totalQuestions,
          createdAt: session.createdAt,
          resumeId: session.resumeId ?? undefined,
        }));
    } catch {
      return [];
    }
  }

  // Load voice interviews from voice API
  async function loadVoiceInterviews(): Promise<UnifiedInterviewItem[]> {
    try {
      const sessions = await voiceInterviewApi.getAllSessions();
      return sessions.map((session: SessionMeta) => ({
        id: `voice-${session.sessionId}`,
        type: 'voice' as const,
        title: session.roleType,
        sessionId: String(session.sessionId),
        status: session.status,
        evaluateStatus: session.evaluateStatus,
        evaluateError: session.evaluateError,
        evaluateStatusUpdatedAt: session.updatedAt,
        overallScore: null,
        actualDuration: session.actualDuration,
        createdAt: session.createdAt,
        voiceSessionId: session.sessionId,
      }));
    } catch {
      return [];
    }
  }

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // Polling for evaluation status
  useEffect(() => {
    const hasEvaluating = items.some(i => isEvaluating(i));

    if (hasEvaluating && !pollingRef.current) {
      pollingRef.current = window.setInterval(() => loadAll(true), 3000);
    } else if (!hasEvaluating && pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    };
  }, [items, loadAll]);

  const handleRowClick = (item: UnifiedInterviewItem) => {
    if (item.type === 'text') {
      onViewInterview(item.sessionId, item.resumeId);
    } else if (item.voiceSessionId) {
      const isLive = isLiveStatus(item.status);
      if (isLive) {
        navigate('/voice-interview', { state: { voiceSessionId: item.voiceSessionId } });
      } else {
        navigate(`/voice-interview/${item.voiceSessionId}/evaluation`);
      }
    }
  };

  const handleDeleteClick = (item: UnifiedInterviewItem, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteItem(item);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteItem) return;
    setDeletingSessionId(deleteItem.sessionId);
    try {
      if (deleteItem.type === 'voice' && deleteItem.voiceSessionId) {
        await voiceInterviewApi.deleteSession(deleteItem.voiceSessionId);
      } else {
        await historyApi.deleteInterview(deleteItem.sessionId);
      }
      await loadAll();
      setDeleteItem(null);
    } catch (err) {
      toast('error', err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingSessionId(null);
    }
  };

  const handleExport = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExporting(sessionId);
    try {
      const blob = await historyApi.exportInterviewPdf(sessionId);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `面试报告_${sessionId.slice(-8)}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch {
      toast('error', '导出失败，请重试');
    } finally {
      setExporting(null);
    }
  };

  const handleRetryVoiceEvaluation = async (
    item: UnifiedInterviewItem,
    e: React.MouseEvent,
  ) => {
    e.stopPropagation();
    if (!item.voiceSessionId) return;

    setRetryingVoiceSessionId(item.voiceSessionId);
    try {
      await voiceInterviewApi.generateEvaluation(item.voiceSessionId);
      await loadAll(true);
    } catch {
      toast('error', '重新生成评估失败，请稍后再试');
    } finally {
      setRetryingVoiceSessionId(null);
    }
  };

  // Filter + search
  const filtered = useMemo(() => items.filter(item => {
    if (typeFilter !== 'all' && item.type !== typeFilter) return false;
    if (searchTerm && !item.title.toLowerCase().includes(searchTerm.toLowerCase())) return false;
    return true;
  }), [items, typeFilter, searchTerm]);

  const stats = useMemo(() => calculateInterviewStats(items), [items]);

  return (
    <motion.div className="w-full" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
      {/* Header */}
      <div className="flex justify-between items-start mb-8 flex-wrap gap-6">
        <div>
          <motion.h1
            className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3"
            initial={{ opacity: 0, x: -20 }}
            animate={{ opacity: 1, x: 0 }}
          >
            <Users className="w-7 h-7 text-primary-500 dark:drop-shadow-[0_0_10px_rgb(var(--skin-accent)/0.8)]" />
            面试记录
          </motion.h1>
          <motion.p
            className="text-slate-500 dark:text-slate-400 mt-1"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.1 }}
          >
            查看和管理所有模拟面试记录
          </motion.p>
        </div>

        <motion.div
          className="flex items-center gap-3 bg-white/85 dark:bg-[rgb(var(--skin-surface-deep)/0.6)] backdrop-blur-md border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.2)] rounded-xl px-4 py-2.5 min-w-[280px] focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 dark:focus-within:border-neon-purple/70 dark:focus-within:ring-neon-purple/20 dark:focus-within:shadow-[0_0_0_1px_rgb(var(--skin-accent)/0.4),0_0_24px_-8px_rgb(var(--skin-accent)/0.6)] transition-all"
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
        >
          <Search className="w-5 h-5 text-slate-400" />
          <input
            type="text"
            placeholder="搜索名称..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="flex-1 outline-none text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent"
          />
        </motion.div>
      </div>

      {/* Stats */}
      {!loading && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <StatCard icon={Users} label="面试总数" value={stats.totalCount} color="bg-primary-500" glow="dark:shadow-[0_0_20px_-4px_rgb(var(--skin-accent-4)/0.8)]" />
          <StatCard icon={CheckCircle} label="已完成" value={stats.completedCount} color="bg-emerald-500" glow="dark:shadow-[0_0_20px_-4px_rgba(0,214,160,0.8)]" />
          <StatCard icon={TrendingUp} label="平均分数" value={stats.averageScore} suffix="分" color="bg-indigo-500" glow="dark:shadow-[0_0_20px_-4px_rgb(var(--skin-accent-2)/0.8)]" />
        </div>
      )}

      {/* Type filter tabs */}
      <div className="flex items-center gap-2 mb-6">
        {([
          { key: 'all', label: '全部' },
          { key: 'text', label: '文字面试' },
          { key: 'voice', label: '语音面试' },
        ] as const).map(tab => (
          <button
            key={tab.key}
            onClick={() => setTypeFilter(tab.key)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-all duration-200 ${
              typeFilter === tab.key
                ? 'bg-primary-500 text-white dark:bg-gradient-to-r dark:from-neon-purple dark:to-neon-blue dark:shadow-[0_0_18px_-6px_rgb(var(--skin-accent)/0.9)]'
                : 'bg-white dark:bg-[rgb(var(--skin-surface)/0.4)] text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.6)] border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.2)]'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Loading */}
      {loading && (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      )}

      {/* Empty */}
      {!loading && filtered.length === 0 && (
        <Card>
          <EmptyState
            icon={<Users className="h-8 w-8" />}
            title="暂无面试记录"
            description="开始一次模拟面试后，记录将显示在这里"
            action={
              <Button onClick={() => navigate('/interview-hub')}>
                去面试中心
              </Button>
            }
          />
        </Card>
      )}

      {/* Table */}
      {!loading && filtered.length > 0 && (
        <motion.div
          className="dark-card overflow-hidden"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <table className="w-full">
            <thead className="bg-slate-50 dark:bg-[rgb(var(--skin-surface-deep)/0.7)] border-b border-slate-100 dark:border-[rgb(var(--skin-accent)/0.14)]">
              <tr>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">类型</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">名称</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">状态</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">得分</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">详情</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">时间</th>
                <th className="text-right px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">操作</th>
              </tr>
            </thead>
            <tbody>
              <AnimatePresence>
                {filtered.map((item, index) => (
                  <motion.tr
                    key={item.id}
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: index * 0.05 }}
                    onClick={() => handleRowClick(item)}
                    className="border-b border-slate-50 dark:border-[rgb(var(--skin-accent)/0.1)] hover:bg-slate-50 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.45)] dark:hover:shadow-[inset_0_0_32px_-16px_rgb(var(--skin-accent)/0.45)] cursor-pointer transition-all duration-300 group"
                  >
                    <td className="px-6 py-4">
                      <TypeBadge item={item} />
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-3">
                        {item.type === 'text' ? (
                          <FileText className="w-5 h-5 text-slate-400" />
                        ) : (
                          <Mic className="w-5 h-5 text-purple-400" />
                        )}
                        <div>
                          <div className="flex items-center gap-2 flex-wrap">
                            <p className="font-medium text-slate-800 dark:text-white">{item.title}</p>
                          </div>
                          <p className="text-xs text-slate-400 dark:text-slate-500">#{item.id.slice(-8)}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-2">
                        <StatusIcon item={item} />
                        <span className="text-sm text-slate-600 dark:text-slate-300">{getStatusText(item)}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      {isEvaluateCompleted(item) && item.overallScore !== null ? (
                        <div className="flex items-center gap-3">
                          <div className="w-16 h-2 bg-slate-100 dark:bg-[rgb(var(--skin-surface-deep)/0.9)] rounded-full overflow-hidden dark:shadow-[inset_0_0_6px_rgb(var(--skin-shadow)/0.9)]">
                            <motion.div
                              className={`h-full ${getScoreProgressColor(item.overallScore)} dark:neon-progress rounded-full dark:shadow-[0_0_10px_rgb(var(--skin-accent-2)/0.8)]`}
                              initial={{ width: 0 }}
                              animate={{ width: `${item.overallScore}%` }}
                              transition={{ duration: 0.8, delay: index * 0.05 }}
                            />
                          </div>
                          <span className="font-bold text-slate-800 dark:text-white">{item.overallScore}</span>
                        </div>
                      ) : isVoiceEvaluationRetryable(item) ? (
                        <span className="text-amber-600 dark:text-amber-400 text-sm">可重新生成</span>
                      ) : isEvaluating(item) ? (
                        <span className="text-blue-500 dark:text-blue-400 text-sm">生成中...</span>
                      ) : isEvaluateFailed(item) ? (
                        <span className="text-red-500 dark:text-red-400 text-sm">失败</span>
                      ) : (
                        <span className="text-slate-400 dark:text-slate-500">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      {item.type === 'text' && item.totalQuestions != null ? (
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-lg text-sm">
                          {item.totalQuestions} 题
                        </span>
                      ) : item.type === 'voice' ? (
                        <span className="text-sm text-slate-500 dark:text-slate-400">
                          {formatDuration(item.actualDuration)}
                        </span>
                      ) : (
                        <span className="text-slate-400">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">
                      {formatDate(item.createdAt)}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center justify-end gap-1">
                        {item.type === 'text' && !isCompletedStatus(item.status) && !isEvaluateCompleted(item) && onContinueInterview && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              onContinueInterview(item.sessionId);
                            }}
                            className="p-2 text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                            title="继续面试"
                          >
                            <PlayCircle className="w-4 h-4" />
                          </button>
                        )}
                        {item.type === 'voice' && isLiveStatus(item.status) && item.voiceSessionId && (
                          <button
                            onClick={(e) => { e.stopPropagation(); navigate('/voice-interview', { state: { voiceSessionId: item.voiceSessionId } }); }}
                            className="p-2 text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                            title="继续面试"
                          >
                            <PlayCircle className="w-4 h-4" />
                          </button>
                        )}
                        {isEvaluateCompleted(item) && item.type === 'text' && (
                          <button
                            onClick={(e) => handleExport(item.sessionId, e)}
                            disabled={exporting === item.sessionId}
                            className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="导出PDF"
                          >
                            {exporting === item.sessionId ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <Download className="w-4 h-4" />
                            )}
                          </button>
                        )}
                        {isEvaluateCompleted(item) && item.type === 'text' && item.resumeId && onRestartInterview && (
                          <button
                            onClick={(e) => { e.stopPropagation(); onRestartInterview(item.resumeId!); }}
                            className="p-2 text-slate-400 hover:text-emerald-500 hover:bg-emerald-50 dark:hover:bg-emerald-900/30 rounded-lg transition-colors"
                            title="重新面试"
                          >
                            <RotateCcw className="w-4 h-4" />
                          </button>
                        )}
                        {isVoiceEvaluationRetryable(item) && item.voiceSessionId && (
                          <button
                            onClick={(e) => handleRetryVoiceEvaluation(item, e)}
                            disabled={retryingVoiceSessionId === item.voiceSessionId}
                            className="p-2 text-amber-500 hover:text-amber-600 hover:bg-amber-50 dark:hover:bg-amber-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="重新生成评估"
                          >
                            {retryingVoiceSessionId === item.voiceSessionId ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <RefreshCw className="w-4 h-4" />
                            )}
                          </button>
                        )}
                        <button
                            onClick={(e) => handleDeleteClick(item, e)}
                            disabled={deletingSessionId === item.sessionId}
                            className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="删除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        <ChevronRight className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-1 transition-all"/>
                      </div>
                    </td>
                  </motion.tr>
                ))}
              </AnimatePresence>
            </tbody>
          </table>
        </motion.div>
      )}

      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem ? { id: 0, sessionId: deleteItem.sessionId } : null}
        itemType="面试记录"
        loading={deletingSessionId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteItem(null)}
      />
    </motion.div>
  );
}
