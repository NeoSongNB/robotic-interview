import {useEffect, useRef, useState} from 'react';
import {Bot, Send, Settings, User} from 'lucide-react';
import ResumeEditProposal from './ResumeEditProposal';
import type {ResumeEditProposal as Proposal} from '../../types/resumeDocument';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
}

interface ResumeChatProps {
  messages: ChatMessage[];
  loading: boolean;
  error: string | null;
  onSend: (message: string) => void;
  proposal: Proposal | null;
  applying: boolean;
  onConfirmProposal: () => void;
  onCancelProposal: () => void;
  onOpenSettings: () => void;
}

const QUICK_PROMPTS = [
  '我叫张三，电话 13800138000，邮箱 zhangsan@example.com',
  '把这段实习经历拆成 2–4 条工作与成果，第一条放与岗位最相关的贡献',
  '帮我看看实习经历里哪些内容重复、哪些该放在前面',
  '添加一个「智能面试平台」项目，使用 Spring Boot 和 RAG 技术',
  '求职意向改为后端开发实习生',
];

function needsCredentialSetup(message: string): boolean {
  return /凭证|API Key|APIKey|模型|设置页|尚未配置/i.test(message);
}

/** 左侧 AI 对话编辑区域。 */
export default function ResumeChat({
  messages, loading, error, onSend, proposal, applying,
  onConfirmProposal, onCancelProposal, onOpenSettings,
}: ResumeChatProps) {
  const [text, setText] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = listRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [messages.length, loading, error, proposal]);

  const submit = () => {
    const value = text.trim();
    if (!value || loading || applying) return;
    onSend(value);
    setText('');
  };

  return (
    <div className="flex flex-col min-h-0 h-full dark-card p-4">
      <div className="flex items-center gap-2 pb-3 border-b border-slate-200 dark:border-[rgb(var(--skin-accent)/0.16)]">
        <Bot className="w-5 h-5 text-primary-500"/>
        <div>
          <h2 className="text-sm font-semibold text-slate-800 dark:text-white">AI 对话编辑</h2>
          <p className="text-xs text-slate-500 dark:text-slate-400">用自然语言录入或修改简历，确认后才会保存</p>
        </div>
      </div>

      <div ref={listRef} className="flex-1 min-h-0 overflow-auto scrollbar-thin py-3 space-y-3">
        {messages.length === 0 && (
          <div className="text-sm text-slate-500 dark:text-slate-400 leading-relaxed">
            <p className="mb-2">你好，我可以帮你逐步完善这份简历：</p>
            <ul className="list-disc pl-5 space-y-1">
              <li>录入姓名、联系方式、学校与求职意向</li>
              <li>添加教育经历、项目经历和多条技术亮点</li>
              <li>润色项目介绍、优化亮点表达、调整亮点顺序</li>
            </ul>
            <p className="mt-2 text-xs">所有修改都会先生成提案，由你确认后才写入简历。</p>
          </div>
        )}
        {messages.map(message => (
          <div
            key={message.id}
            className={`flex gap-2 ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}
          >
            {message.role === 'assistant' && (
              <span className="flex-none w-6 h-6 rounded-full bg-primary-500/15 flex items-center justify-center">
                <Bot className="w-3.5 h-3.5 text-primary-500"/>
              </span>
            )}
            <div
              className={`max-w-[85%] rounded-xl px-3 py-2 text-sm whitespace-pre-wrap break-words ${
                message.role === 'user'
                  ? 'bg-primary-500 text-white'
                  : 'bg-slate-100 dark:bg-[rgb(var(--skin-surface-deep)/0.6)] text-slate-700 dark:text-slate-200'
              }`}
            >
              {message.content}
            </div>
            {message.role === 'user' && (
              <span className="flex-none w-6 h-6 rounded-full bg-slate-200 dark:bg-[rgb(var(--skin-surface-deep)/0.8)] flex items-center justify-center">
                <User className="w-3.5 h-3.5 text-slate-600 dark:text-slate-300"/>
              </span>
            )}
          </div>
        ))}
        {loading && (
          <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
            <span className="w-2 h-2 rounded-full bg-primary-500 animate-pulse"/>
            AI 正在理解你的需求并生成修改提案...
          </div>
        )}
        {error && (
          <div className="rounded-lg bg-red-50 dark:bg-red-500/10 px-3 py-2 text-xs text-red-700 dark:text-red-300">
            <p className="whitespace-pre-wrap">{error}</p>
            <button
              onClick={onOpenSettings}
              className={`mt-1 inline-flex items-center gap-1 underline ${needsCredentialSetup(error) ? '' : 'opacity-70'}`}
            >
              <Settings className="w-3 h-3"/>前往「设置 - 我的 AI 配置」
            </button>
          </div>
        )}
      </div>

      {proposal && (
        <div className="mb-3">
          <ResumeEditProposal
            proposal={proposal}
            applying={applying}
            onConfirm={onConfirmProposal}
            onCancel={onCancelProposal}
          />
        </div>
      )}

      <div className="mb-2 flex flex-wrap gap-1.5">
        {QUICK_PROMPTS.map(prompt => (
          <button
            key={prompt}
            onClick={() => setText(prompt)}
            disabled={loading || applying}
            className="text-[11px] px-2 py-1 rounded-full border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.25)] text-slate-500 dark:text-slate-400 hover:text-primary-500 hover:border-primary-500/50 transition-colors disabled:opacity-50"
            title={prompt}
          >
            {prompt.length > 18 ? `${prompt.slice(0, 18)}…` : prompt}
          </button>
        ))}
      </div>

      <div className="flex items-end gap-2">
        <textarea
          value={text}
          onChange={event => setText(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
          rows={2}
          maxLength={2000}
          disabled={loading || applying}
          placeholder="例如：帮我增加一个项目，突出 Multi-Agent 和 RAG 技术亮点"
          className="dark-input flex-1 resize-none px-3 py-2 rounded-lg text-sm disabled:opacity-60"
        />
        <button
          onClick={submit}
          disabled={!text.trim() || loading || applying}
          className="btn-primary px-3 py-2.5 rounded-lg disabled:opacity-50"
          title="发送"
        >
          <Send className="w-4 h-4"/>
        </button>
      </div>
    </div>
  );
}
