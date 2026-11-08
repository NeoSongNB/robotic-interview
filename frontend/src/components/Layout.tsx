import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {BookOpen, ChevronRight, Database, FileStack, LogOut, MessageSquare, Moon, Settings, Sparkles, Sun, Users,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useState} from 'react';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';
import ThemeSkinSwitcher from './ThemeSkinSwitcher';
import {ROUTES} from '../constants/routes';
import {useAuth} from '../contexts/AuthContext';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const {user, logout} = useAuth();
  const [loggingOut, setLoggingOut] = useState(false);
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
  } | null>(null);

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate(ROUTES.interviewCreate(crypto.randomUUID()), {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
          },
        },
      });
      return;
    }

    const params = new URLSearchParams({
      skillId: config.skillId,
      difficulty: config.difficulty,
    });
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
  };

  // 退出登录：后端清除 HttpOnly Cookie，前端仅重置状态并回到登录页
  const handleLogout = async () => {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await logout();
      navigate('/login', {replace: true});
    } finally {
      setLoggingOut(false);
    }
  };

  // 按业务模块组织的导航项
  const navGroups: NavGroup[] = [
    {
      id: 'interview',
      title: '面试准备',
      items: [
        { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
        { id: 'interview-hub', path: '/interview-hub', label: '模拟面试', icon: Sparkles, description: '文字/语音面试练习' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'kb-interview', path: '/knowledgebase-interview', label: '知识库面试', icon: BookOpen, description: '题库维护与面试' },
        { id: 'chat', path: '/knowledgebase/chat', label: '问答助手', icon: MessageSquare, description: '基于知识库问答' },
      ],
    },
    {
      id: 'system',
      title: '系统',
      items: [
        { id: 'settings', path: '/settings', label: '设置', icon: Settings, description: '管理模型和语音服务' },
      ],
    },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/interview-hub') {
      return currentPath === '/interview-hub'
        || currentPath === ROUTES.interview
        || currentPath.startsWith('/interview/')
        || currentPath.startsWith('/voice-interview');
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="flex min-h-screen">
      {/* 左侧边栏：深蓝半透明玻璃质感 */}
      <aside className="w-64 bg-white/90 dark:bg-[var(--skin-navy)]/80 backdrop-blur-2xl border-r border-slate-100 dark:border-[rgb(var(--skin-accent)/0.14)] fixed h-screen left-0 top-0 z-50 flex flex-col dark:shadow-[8px_0_40px_-16px_rgb(var(--skin-shadow)/0.9)]">
        {/* Logo */}
        <div className="p-6 border-b border-slate-100 dark:border-[rgb(var(--skin-accent)/0.12)] flex items-center justify-between">
          <Link to="/history" className="flex items-center gap-3 group">
            <div className="w-10 h-10 neon-icon-box rounded-xl flex items-center justify-center text-white transition-transform duration-300 group-hover:scale-105 group-hover:rotate-6">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <span className="text-lg font-bold text-slate-800 dark:text-white tracking-tight block">AI Interview</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">智能面试助手</span>
            </div>
          </Link>
        </div>

        {/* 外观：页面风格 + 明暗模式 */}
        <div className="px-4 pb-2 space-y-2">
          <ThemeSkinSwitcher />
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-slate-100 dark:bg-[rgb(var(--skin-surface)/0.5)] dark:border dark:border-[rgb(var(--skin-accent)/0.2)] text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.6)] dark:hover:border-[rgb(var(--skin-accent)/0.4)] transition-all duration-200"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span className="text-sm font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span className="text-sm font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 p-4 overflow-y-auto">
          <div className="space-y-6">
            {navGroups.map((group) => (
              <div key={group.id}>
                <div className="px-3 mb-2">
                  <span className="text-xs font-semibold text-slate-400 dark:text-section-label uppercase tracking-wider">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const active = isActive(item.path);

                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all duration-200 dark:hover:translate-x-1
                          ${active
                            ? 'bg-primary-50 dark:bg-[rgb(var(--skin-surface-active)/0.9)] dark:bg-gradient-to-r dark:from-neon-purple/20 dark:via-primary-500/15 dark:to-neon-blue/10 text-primary-600 dark:text-white dark:shadow-[inset_0_0_0_1px_rgb(var(--skin-accent)/0.4),0_0_24px_-8px_rgb(var(--skin-accent)/0.6)]'
                            : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-[rgb(var(--skin-surface-hi)/0.45)] hover:text-slate-900 dark:hover:text-white'
                          }`}
                      >
                        <div className={`w-9 h-9 rounded-lg flex items-center justify-center transition-all duration-300
                          ${active
                            ? 'bg-primary-100 dark:neon-icon-box dark:text-white dark:shadow-[0_0_14px_-2px_rgb(var(--skin-accent)/0.8)] text-primary-600'
                            : 'bg-slate-100 dark:bg-[rgb(var(--skin-surface)/0.7)] text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-[rgb(var(--skin-surface-hi)/0.7)] group-hover:text-slate-700 dark:group-hover:text-white'
                          }`}
                        >
                          <item.icon className="w-5 h-5" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          {item.description && (
                            <span className="text-xs text-slate-400 dark:text-slate-500 truncate block">
                              {item.description}
                            </span>
                          )}
                        </div>
                        {active && <ChevronRight className="w-4 h-4 text-primary-400 dark:text-neon-cyan dark:drop-shadow-[0_0_6px_rgb(var(--skin-accent-3)/0.8)]" />}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        {/* 底部信息：当前用户 + 退出登录 */}
        <div className="p-4 border-t border-slate-100 dark:border-[rgb(var(--skin-accent)/0.12)] space-y-3">
          <div className="flex items-center gap-3 px-3">
            <div className="w-9 h-9 rounded-full bg-primary-100 dark:bg-gradient-to-br dark:from-neon-purple/40 dark:to-neon-blue/40 dark:shadow-[0_0_14px_-4px_rgb(var(--skin-accent)/0.8)] text-primary-600 dark:text-white flex items-center justify-center text-sm font-semibold uppercase shrink-0">
              {(user?.username ?? '?').slice(0, 1)}
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">
                {user?.username ?? '未登录'}
              </p>
              <p className="text-xs text-slate-400 dark:text-slate-500">
                {user?.role === 'ADMIN' ? '管理员' : '普通用户'}
              </p>
            </div>
            <button
              onClick={handleLogout}
              disabled={loggingOut}
              title="退出登录"
              aria-label="退出登录"
              className="p-2 rounded-lg text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-50"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
          <div className="px-3 py-2 bg-gradient-to-r from-primary-50 to-indigo-50 dark:from-neon-purple/15 dark:via-primary-500/10 dark:to-neon-blue/15 dark:border dark:border-[rgb(var(--skin-accent)/0.2)] rounded-xl">
            <p className="text-xs text-primary-600 dark:text-neon-cyan dark:drop-shadow-[0_0_6px_rgb(var(--skin-accent-3)/0.4)] font-medium">AI 面试助手 v1.0</p>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">Powered by AI</p>
          </div>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="flex-1 ml-64 p-10 min-h-screen overflow-y-auto">
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.3 }}
        >
          <Outlet context={{ openInterviewModalWithResume }} />
        </motion.div>
      </main>

      {/* 统一面试弹窗 */}
      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.defaultResumeId == null}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />
    </div>
  );
}
