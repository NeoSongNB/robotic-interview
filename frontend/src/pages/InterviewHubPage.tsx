import { useState, useEffect, useCallback } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import {
  ChevronDown, ChevronUp, FileStack, FileText, Loader2, Mic,
  RefreshCw, Sparkles,
} from 'lucide-react';
import { type SkillDTO } from '../api/skill';
import { userCredentialApi } from '../api/userCredential';
import type { UserCredential } from '../types/userCredential';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { voiceInterviewApi, type SessionMeta } from '../api/voiceInterview';
import { getSkillIcon } from '../utils/skillIcons';
import { getTemplateName } from '../utils/voiceInterview';
import { getScoreTextColor } from '../utils/score';
import { formatDateTime } from '../utils/date';
import {
  useInterviewConfig,
  CUSTOM_SKILL_ID,
  type InterviewMode,
  DIFFICULTY_OPTIONS,
} from '../hooks/useInterviewConfig';
import {ROUTES} from '../constants/routes';

// 统一的面试记录项
interface RecentInterviewItem {
  id: string;
  type: 'text' | 'voice';
  title: string;
  status: string;
  evaluateStatus?: string | null;
  overallScore: number | null;
  createdAt: string;
  voiceSessionId?: number;
}

export default function InterviewHubPage() {
  const navigate = useNavigate();

  const config = useInterviewConfig({ autoLoad: false });
  const [chatProviders, setChatProviders] = useState<(UserCredential & { displayName: string })[]>([]);

  // === 最近面试记录 ===
  const [recentInterviews, setRecentInterviews] = useState<RecentInterviewItem[]>([]);
  const [loadingRecent, setLoadingRecent] = useState(false);

  const loadRecentInterviews = useCallback(async (allSkills: SkillDTO[]) => {
    setLoadingRecent(true);
    try {
      const [textSessions, voiceSessions] = await Promise.all([
        interviewApi.listSessions().catch(() => [] as TextSessionMeta[]),
        voiceInterviewApi.getAllSessions().catch(() => [] as SessionMeta[]),
      ]);

      const items: RecentInterviewItem[] = [
        ...textSessions.map(s => ({
          id: s.sessionId,
          type: 'text' as const,
          title: getTemplateName(s.skillId, allSkills),
          status: s.status,
          evaluateStatus: s.evaluateStatus,
          overallScore: s.overallScore,
          createdAt: s.createdAt,
        })),
        ...voiceSessions.map(s => ({
          id: `voice-${s.sessionId}`,
          type: 'voice' as const,
          title: s.roleType || '语音面试',
          status: s.status,
          overallScore: null,
          createdAt: s.createdAt,
          voiceSessionId: s.sessionId,
        })),
      ];

      items.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
      setRecentInterviews(items.slice(0, 5));
    } catch (err) {
      console.error('Failed to load recent interviews:', err);
    } finally {
      setLoadingRecent(false);
    }
  }, []);

  // 初始加载：skills 和 resumes 并行，再用 skills 加载面试记录
  useEffect(() => {
    const init = async () => {
      const [skills] = await Promise.all([config.loadSkills(), config.loadResumes()]);
      await loadRecentInterviews(skills);
    };
    init();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([userCredentialApi.list(), userCredentialApi.providers()])
      .then(([credentials, presets]) => {
        if (cancelled) return;
        const names = new Map(presets.map(preset => [preset.providerKey, preset.displayName]));
        setChatProviders(credentials
          .filter(credential => credential.configured && credential.chatEnabled && names.has(credential.providerKey))
          .map(credential => ({ ...credential, displayName: names.get(credential.providerKey) ?? credential.providerKey })));
      })
      .catch(() => {
        // 配置列表暂不可用时，面试仍可按默认模型开始。
      });
    return () => { cancelled = true; };
  }, []);

  const handleStart = () => {
    const selectedSkill = config.selectedSkill;
    const skillName = selectedSkill?.name || '自定义';

    if (config.isCustomStartDisabled) {
      return;
    }

    if (config.mode === 'text') {
      navigate(ROUTES.interviewCreate(crypto.randomUUID()), {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            skillName,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
            jdText: config.isCustomSkill ? config.parsedCustomJdText : undefined,
            customCategories: config.isCustomSkill ? config.customCategories : undefined,
          },
        },
      });
    } else {
      const params = new URLSearchParams({ skillId: config.skillId, difficulty: config.difficulty });
      navigate(`/voice-interview?${params.toString()}`, {
        state: {
          voiceConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            techEnabled: true,
            projectEnabled: true,
            hrEnabled: true,
            plannedDuration: config.plannedDuration,
            resumeId: config.resumeId,
            llmProvider: config.llmProvider,
          },
        },
      });
    }
  };

  return (
    <div className="max-w-5xl mx-auto">
      {/* 页面标题 */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
          <Sparkles className="w-7 h-7 text-primary-500" />
          模拟面试
        </h1>
        <p className="text-slate-500 dark:text-slate-400 mt-1">选择面试模式和方向，快速开始练习</p>
      </div>

      {/* 配置区域 */}
      <div className="dark-card p-6 mb-8">
        <div className="space-y-6">
          {/* 面试模式 */}
          <div>
            <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
              面试模式
            </label>
            <div className="grid grid-cols-2 gap-3">
              {([
                {
                  value: 'text' as InterviewMode,
                  label: '文字面试',
                  icon: FileText,
                  desc: '推荐：更稳定，更适合系统化刷题与复盘',
                  recommended: true,
                },
                {
                  value: 'voice' as InterviewMode,
                  label: '语音面试',
                  icon: Mic,
                  desc: '实时语音对话，更偏临场模拟',
                  recommended: false,
                },
              ]).map(opt => {
                const Icon = opt.icon;
                const selected = config.mode === opt.value;
                return (
                  <button
                    key={opt.value}
                    onClick={() => config.setMode(opt.value)}
                    className={`flex items-center gap-3 p-4 rounded-xl border-2 transition-all duration-200 text-left neon-border-flow
                      ${selected
                        ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20 dark:neon-selected'
                        : 'border-slate-200 dark:border-[rgb(var(--skin-accent)/0.2)] bg-white dark:bg-[rgb(var(--skin-surface)/0.4)] hover:border-slate-300 dark:hover:border-[rgb(var(--skin-accent)/0.5)]'
                      }`}
                  >
                    <Icon className={`w-6 h-6 flex-shrink-0 transition-transform duration-300 ${selected ? 'text-primary-500 dark:text-neon-cyan dark:drop-shadow-[0_0_8px_rgb(var(--skin-accent-3)/0.8)]' : 'text-slate-400'}`} />
                    <div className="min-w-0">
                      <p className={`font-semibold text-sm flex items-center gap-2 ${selected ? 'text-primary-700 dark:text-primary-300' : 'text-slate-900 dark:text-white'}`}>
                        <span>{opt.label}</span>
                        {opt.recommended && (
                          <span className="px-1.5 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300">
                            推荐
                          </span>
                        )}
                      </p>
                      <p className="text-xs text-slate-500 dark:text-slate-400">{opt.desc}</p>
                    </div>
                  </button>
                );
              })}
            </div>
          </div>

          {/* 面试方向 */}
          <div>
            <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
              面试方向
            </label>
            {config.loadingSkills ? (
              <div className="flex items-center gap-2 py-4 text-slate-400">
                <Loader2 className="w-4 h-4 animate-spin" />
                <span className="text-sm">加载中...</span>
              </div>
            ) : (
              <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2">
                {config.skills.map(skill => {
                  const selected = config.skillId === skill.id;
                  const IconComponent = getSkillIcon(skill.id);
                  const fallbackEmoji = skill.display?.icon || '📋';
                  return (
                    <button
                      key={skill.id}
                      onClick={() => config.setSkillId(skill.id)}
                      className={`flex items-center gap-2.5 p-3 rounded-xl border-2 transition-all duration-200 text-left neon-border-flow
                        ${selected
                          ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20 dark:neon-selected'
                          : 'border-slate-200 dark:border-[rgb(var(--skin-accent)/0.2)] bg-white dark:bg-[rgb(var(--skin-surface)/0.4)] hover:border-slate-300 dark:hover:border-[rgb(var(--skin-accent)/0.5)]'
                        }`}
                    >
                      <div className={`w-8 h-8 rounded-lg flex items-center justify-center text-sm flex-shrink-0 transition-transform duration-300 ${
                        selected ? skill.display?.iconBg || 'bg-primary-100 dark:bg-primary-900/50 dark:shadow-[0_0_12px_-2px_rgb(var(--skin-accent)/0.8)]' : 'bg-slate-100 dark:bg-[rgb(var(--skin-surface-deep)/0.7)]'
                      }`}>
                        {IconComponent
                          ? <IconComponent className={`w-4 h-4 ${selected ? (skill.display?.iconColor || 'text-primary-600') : 'text-slate-500 dark:text-slate-400'}`} />
                          : <span className={selected ? (skill.display?.iconColor || 'text-primary-600') : ''}>{fallbackEmoji}</span>
                        }
                      </div>
                      <div className="flex-1 min-w-0">
                        <span className={`text-xs font-medium block truncate ${selected ? 'text-primary-700 dark:text-primary-300' : 'text-slate-700 dark:text-slate-300'}`}>
                          {skill.name}
                        </span>
                      </div>
                    </button>
                  );
                })}
                {/* 自定义按钮 */}
                <button
                  onClick={() => config.setSkillId(CUSTOM_SKILL_ID)}
                  className={`flex items-center gap-2.5 p-3 rounded-xl border-2 border-dashed transition-all duration-200 text-left
                    ${config.isCustomSkill
                      ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20 dark:neon-selected'
                      : 'border-slate-200 dark:border-[rgb(var(--skin-accent)/0.25)] hover:border-primary-300 dark:hover:border-[rgb(var(--skin-accent)/0.55)]'
                    }`}
                >
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${
                    config.isCustomSkill ? 'bg-primary-100 dark:bg-primary-900/50 dark:shadow-[0_0_12px_-2px_rgb(var(--skin-accent)/0.8)]' : 'bg-slate-100 dark:bg-[rgb(var(--skin-surface-deep)/0.7)]'
                  }`}>
                    {(() => {
                      const CustomIcon = getSkillIcon(CUSTOM_SKILL_ID);
                      return CustomIcon
                        ? <CustomIcon className={`w-4 h-4 ${config.isCustomSkill ? 'text-primary-600 dark:text-primary-400' : 'text-slate-500 dark:text-slate-400'}`} />
                        : <span className="text-sm">✨</span>;
                    })()}
                  </div>
                  <span className={`text-xs font-medium ${config.isCustomSkill ? 'text-primary-700 dark:text-primary-300' : 'text-slate-500 dark:text-slate-400'}`}>
                    自定义 JD
                  </span>
                </button>
              </div>
            )}
          </div>

          {/* 自定义 JD 输入 */}
          <AnimatePresence>
            {config.isCustomSkill && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                className="overflow-hidden"
              >
                <div className="space-y-3 bg-slate-50 dark:bg-[rgb(var(--skin-navy-rgb)/0.6)] backdrop-blur-md rounded-xl p-4 border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.18)]">
                  <textarea
                    value={config.customJdText}
                    onChange={e => config.setCustomJdText(e.target.value)}
                    placeholder="粘贴目标岗位的职位描述（JD），至少 50 字..."
                    rows={4}
                    className="w-full px-4 py-3 rounded-xl border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.25)]
                      bg-white dark:bg-[rgb(var(--skin-surface-deep)/0.6)] text-sm text-slate-900 dark:text-white
                      placeholder:text-slate-400 dark:placeholder:text-slate-500 resize-none focus:outline-none focus:ring-2
                      focus:ring-primary-500/50 dark:focus:ring-neon-purple/30 focus:border-primary-400 dark:focus:border-neon-purple/60 transition-shadow"
                  />
                  <button
                    onClick={config.handleParseJd}
                    disabled={config.parsingJd || !config.customJdText}
                    className="btn-primary flex items-center gap-2 px-4 py-2 text-sm rounded-lg
                      disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {config.parsingJd ? <Loader2 className="w-4 h-4 animate-spin" /> : <Sparkles className="w-4 h-4" />}
                    解析面试方向
                  </button>
                  {config.customCategories.length > 0 && (
                    <div className="flex flex-wrap gap-2">
                      {config.customCategories.map((cat, i) => (
                        <span
                          key={i}
                          className="px-3 py-1 text-xs font-medium rounded-full bg-primary-100 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300"
                        >
                          {cat.label}
                          <span className="ml-1 text-[10px] text-primary-500">({cat.priority})</span>
                        </span>
                      ))}
                    </div>
                  )}
                  {config.jdNeedsReparse && (
                    <p className="text-xs text-amber-600 dark:text-amber-400">
                      JD 已修改，请重新解析后再开始面试。
                    </p>
                  )}
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          {/* 难度 */}
          <div>
            <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
              难度
            </label>
            <div className="grid grid-cols-3 gap-3">
              {DIFFICULTY_OPTIONS.map(opt => {
                const selected = config.difficulty === opt.value;
                return (
                  <button
                    key={opt.value}
                    onClick={() => config.setDifficulty(opt.value)}
                    className={`py-3 px-4 rounded-xl border-2 transition-all duration-200 text-center neon-border-flow
                      ${selected
                        ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20 dark:neon-selected'
                        : 'border-slate-200 dark:border-[rgb(var(--skin-accent)/0.2)] bg-white dark:bg-[rgb(var(--skin-surface)/0.4)] hover:border-slate-300 dark:hover:border-[rgb(var(--skin-accent)/0.5)]'
                      }`}
                  >
                    <p className={`text-sm font-semibold ${selected ? 'text-primary-700 dark:text-white dark:drop-shadow-[0_0_6px_rgb(var(--skin-accent)/0.6)]' : 'text-slate-700 dark:text-slate-300'}`}>
                      {opt.label}
                    </p>
                    <p className="text-xs text-slate-400">{opt.desc}</p>
                  </button>
                );
              })}
            </div>
          </div>

          {/* 更多选项 */}
          <button
            onClick={() => config.setShowMore(!config.showMore)}
            className="w-full flex items-center gap-2 py-2 text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300 transition-colors"
          >
            {config.showMore ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
            <span>更多选项</span>
            <div className="flex-1 border-t border-slate-200 dark:border-slate-700" />
          </button>

          <AnimatePresence>
            {config.showMore && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                className="overflow-hidden space-y-4"
              >
                {/* 简历选择 */}
                <div className="bg-gradient-to-br from-primary-50/80 to-blue-50/80 dark:from-primary-900/20 dark:to-blue-900/10 rounded-xl p-4 border border-primary-100 dark:border-primary-800/30">
                  <div className="flex items-center gap-3 mb-3">
                    <FileStack className="w-5 h-5 text-primary-500" />
                    <p className="font-semibold text-sm text-primary-900 dark:text-primary-100">
                      基于简历面试（可选）
                    </p>
                  </div>
                  <select
                    value={config.resumeId || ''}
                    onChange={e => config.setResumeId(e.target.value ? parseInt(e.target.value) : undefined)}
                    className="w-full px-4 py-2.5 rounded-lg border border-primary-200 dark:border-primary-700/50
                      bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white
                      focus:outline-none focus:ring-2 focus:ring-primary-500/50 transition-shadow"
                  >
                    <option value="">不使用简历（通用提问）</option>
                    {config.resumes.map(r => (
                      <option key={r.id} value={r.id}>{r.filename}</option>
                    ))}
                  </select>
                </div>

                {chatProviders.length > 0 && (
                  <div>
                    <label htmlFor="interview-chat-provider" className="mb-2 block text-sm font-semibold text-slate-700 dark:text-slate-200">
                      聊天模型
                    </label>
                    <select
                      id="interview-chat-provider"
                      value={config.llmProvider}
                      onChange={event => config.setLlmProvider(event.target.value)}
                      className="w-full rounded-lg border border-primary-200 bg-white px-4 py-2.5 text-sm text-slate-900
                        focus:outline-none focus:ring-2 focus:ring-primary-500/50 dark:border-primary-700/50 dark:bg-slate-800 dark:text-white"
                    >
                      <option value="">自动选择</option>
                      {chatProviders.map(provider => (
                        <option key={provider.providerKey} value={provider.providerKey}>
                          {provider.displayName}{provider.modelName ? ` · ${provider.modelName}` : ''}
                        </option>
                      ))}
                    </select>
                  </div>
                )}

                {/* 文字面试 - 题目数 */}
                {config.mode === 'text' && (
                  <div>
                    <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
                      题目数量
                    </label>
                    <div className="flex gap-2">
                      {[6, 8, 10, 12].map(n => (
                        <button
                          key={n}
                          onClick={() => config.setQuestionCount(n)}
                          className={`flex-1 py-2 rounded-lg text-sm font-medium transition-all
                            ${config.questionCount === n
                              ? 'bg-primary-500 text-white shadow-sm dark:shadow-[0_0_16px_-4px_rgb(var(--skin-accent)/0.9)]'
                              : 'bg-slate-100 dark:bg-[rgb(var(--skin-surface)/0.5)] dark:border dark:border-[rgb(var(--skin-accent)/0.18)] text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.6)]'
                            }`}
                        >
                          {n} 题
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {/* 语音面试 - 时长 */}
                {config.mode === 'voice' && (
                  <div className="bg-slate-50/80 dark:bg-slate-900/50 rounded-xl p-4 border border-slate-200 dark:border-slate-700">
                    <div className="flex items-center justify-between mb-3">
                      <p className="font-semibold text-sm text-slate-900 dark:text-white">计划面试时长</p>
                      <div className="text-2xl font-bold tabular-nums text-primary-600 dark:text-primary-400">
                        {config.plannedDuration}
                        <span className="text-xs font-normal text-slate-400 ml-0.5">min</span>
                      </div>
                    </div>
                    <input
                      type="range"
                      min="15"
                      max="60"
                      step="5"
                      value={config.plannedDuration}
                      onChange={e => config.setPlannedDuration(parseInt(e.target.value))}
                      className="w-full h-2 bg-slate-200 dark:bg-slate-700 rounded-lg appearance-none cursor-pointer
                        [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-4
                        [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:rounded-full
                        [&::-webkit-slider-thumb]:bg-primary-500 [&::-webkit-slider-thumb]:cursor-pointer
                        [&::-webkit-slider-thumb]:shadow-md [&::-webkit-slider-thumb]:shadow-primary-500/30"
                    />
                  </div>
                )}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* 开始面试按钮 */}
        <div className="mt-6 pt-6 border-t border-slate-100 dark:border-slate-700">
          <motion.button
            onClick={handleStart}
            whileHover={{ scale: 1.01 }}
            whileTap={{ scale: 0.99 }}
            disabled={config.isCustomStartDisabled}
            className="btn-primary w-full px-6 py-3 rounded-xl font-semibold text-sm
              disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:translate-y-0"
          >
            开始{config.mode === 'text' ? '文字' : '语音'}面试
          </motion.button>
        </div>
      </div>

      {/* 最近面试记录 */}
      <div className="dark-card p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-slate-800 dark:text-white">最近面试记录</h2>
          <Link
            to="/interviews"
            className="text-sm text-primary-500 hover:text-primary-600 font-medium transition-colors"
          >
            查看全部
          </Link>
        </div>

        {loadingRecent ? (
          <div className="flex items-center justify-center py-10">
            <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
          </div>
        ) : recentInterviews.length === 0 ? (
          <div className="text-center py-10">
            <p className="text-slate-400 dark:text-slate-500 text-sm">暂无面试记录，选择方向开始第一次面试吧</p>
          </div>
        ) : (
          <div className="space-y-2">
            {recentInterviews.map((item, index) => {
              const isCompleted = item.evaluateStatus === 'COMPLETED' || item.status === 'EVALUATED';
              const isEvaluating = item.evaluateStatus === 'PENDING' || item.evaluateStatus === 'PROCESSING';
              return (
                <motion.div
                  key={item.id}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.05 }}
                  onClick={() => {
                    if (item.type === 'text') {
                      navigate(`/interviews/${item.id}`);
                    } else if (item.voiceSessionId) {
                      navigate(`/voice-interview/${item.voiceSessionId}/evaluation`);
                    }
                  }}
                  className="flex items-center gap-4 p-4 rounded-xl hover:bg-slate-50 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.45)] dark:hover:shadow-[inset_0_0_24px_-12px_rgb(var(--skin-accent)/0.4)] transition-all duration-300 cursor-pointer group"
                >
                  {/* 类型图标 */}
                  <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 transition-transform duration-300 group-hover:scale-110 ${
                    item.type === 'text'
                      ? 'bg-blue-100 dark:bg-neon-blue/15 dark:border dark:border-neon-blue/30 text-blue-600 dark:text-neon-cyan dark:shadow-[0_0_14px_-4px_rgb(var(--skin-accent-2)/0.7)]'
                      : 'bg-emerald-100 dark:bg-glow-success/15 dark:border dark:border-glow-success/30 text-emerald-600 dark:text-glow-success dark:shadow-[0_0_14px_-4px_rgba(0,214,160,0.6)]'
                  }`}>
                    {item.type === 'text' ? <FileText className="w-5 h-5" /> : <Mic className="w-5 h-5" />}
                  </div>

                  {/* 信息 */}
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-sm text-slate-800 dark:text-white truncate">{item.title}</span>
                      <span className={`px-2 py-0.5 rounded text-[10px] font-medium ${
                        item.type === 'text'
                          ? 'bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400'
                          : 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400'
                      }`}>
                        {item.type === 'text' ? '文字' : '语音'}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 mt-1">
                      <span className="text-xs text-slate-400 dark:text-slate-500">
                        {formatDateTime(item.createdAt)}
                      </span>
                      {isEvaluating && (
                        <span className="flex items-center gap-1 text-xs text-blue-500">
                          <RefreshCw className="w-3 h-3 animate-spin" /> 评估中
                        </span>
                      )}
                      {isCompleted && item.overallScore !== null && (
                        <span className="text-xs text-slate-600 dark:text-slate-300">
                          得分 <span className={`font-bold ${getScoreTextColor(item.overallScore!)}`}>{item.overallScore}</span>
                        </span>
                      )}
                    </div>
                  </div>

                  {/* 箭头 */}
                  <svg className="w-4 h-4 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-0.5 transition-all flex-shrink-0" viewBox="0 0 24 24" fill="none">
                    <polyline points="9,18 15,12 9,6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                </motion.div>
              );
            })}
          </div>
        )}
      </div>

    </div>
  );
}
