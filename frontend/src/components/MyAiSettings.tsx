import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Eye,
  EyeOff,
  KeyRound,
  Loader2,
  Mic,
  MessageSquare,
  PlugZap,
  Save,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import { userCredentialApi } from '../api/userCredential';
import { getErrorMessage } from '../api/request';
import type {
  CredentialStatus,
  CredentialTestResult,
  ProviderPreset,
  UserCredential,
} from '../types/userCredential';
import ConfirmDialog from './ConfirmDialog';

/**
 * 「我的 AI 配置」（BYOK）。
 *
 * <p>安全约定：</p>
 * <ul>
 *   <li>API Key 只存在于组件内存（useState），提交成功后立即清空；</li>
 *   <li>不写 localStorage / sessionStorage / Cookie，刷新即丢失；</li>
 *   <li>后端只回传脱敏信息（末 4 位 + 更新时间），前端永远拿不到完整 Key；</li>
 *   <li>providerKey 只能从服务端下发的白名单中选择，前端不提交 baseUrl。</li>
 * </ul>
 *
 * <p>阶段二起提供<b>真实</b>连接测试：后端会用当前用户自己的凭证实际请求供应商，
 * 前端只展示脱敏后的结果，绝不读写明文 Key。</p>
 */
export default function MyAiSettings() {
  const [presets, setPresets] = useState<ProviderPreset[]>([]);
  const [credentials, setCredentials] = useState<UserCredential[]>([]);
  const [status, setStatus] = useState<CredentialStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [providerKey, setProviderKey] = useState('');
  // 仅内存态：提交后立即清空
  const [apiKey, setApiKey] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [modelName, setModelName] = useState('');

  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [notice, setNotice] = useState('');

  /** 正在测试的目标：'form' 表示当前表单凭证，其它为 providerKey */
  const [testing, setTesting] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<CredentialTestResult | null>(null);

  const [pendingDelete, setPendingDelete] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  const current = useMemo(
    () => credentials.find(item => item.providerKey === providerKey) ?? null,
    [credentials, providerKey],
  );
  const currentPreset = useMemo(
    () => presets.find(item => item.providerKey === providerKey) ?? null,
    [presets, providerKey],
  );

  const reload = useCallback(async () => {
    const [presetList, credentialList, statusInfo] = await Promise.all([
      userCredentialApi.providers(),
      userCredentialApi.list(),
      userCredentialApi.status(),
    ]);
    setPresets(presetList);
    setCredentials(credentialList);
    setStatus(statusInfo);
    return { presetList, credentialList, statusInfo };
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { presetList, credentialList } = await reload();
        if (cancelled) return;
        const firstKey = presetList[0]?.providerKey ?? '';
        setProviderKey(firstKey);
        const existing = credentialList.find(item => item.providerKey === firstKey);
        setModelName(existing?.modelName ?? presetList[0]?.defaultModel ?? '');
      } catch (error) {
        if (!cancelled) {
          setLoadError(getErrorMessage(error));
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reload]);

  const handleProviderChange = (nextKey: string) => {
    setProviderKey(nextKey);
    setFormError('');
    setNotice('');
    setTestResult(null);
    // 切换供应商时立即丢弃上一条未提交的 Key，避免串到别的供应商
    setApiKey('');
    setShowApiKey(false);
    const existing = credentials.find(item => item.providerKey === nextKey);
    const preset = presets.find(item => item.providerKey === nextKey);
    setModelName(existing?.modelName ?? preset?.defaultModel ?? '');
  };

  /**
   * 真实连接测试。
   *
   * <p>表单里填了新 Key 时用「临时凭证」接口验证（不落库）；
   * 否则用「已保存凭证」接口验证。两种情况都由后端真实请求供应商。</p>
   */
  const handleTest = async (target: string) => {
    if (testing) return;
    const targetProvider = target === 'form' ? providerKey : target;
    setFormError('');
    setNotice('');
    setTestResult(null);
    if (!targetProvider) {
      setFormError('请选择供应商');
      return;
    }
    setTesting(target);
    try {
      const trimmedKey = target === 'form' ? apiKey.trim() : '';
      const result = trimmedKey
        ? await userCredentialApi.testTransient({
            providerKey: targetProvider,
            apiKey: trimmedKey,
            modelName: modelName.trim() || undefined,
          })
        : await userCredentialApi.test(targetProvider);
      setTestResult(result);
    } catch (error) {
      // 后端已对错误信息脱敏，这里只展示消息本身
      setTestResult({
        success: false,
        providerKey: targetProvider,
        modelName: modelName.trim(),
        latencyMs: 0,
        message: getErrorMessage(error),
        errorCode: null,
      });
    } finally {
      setTesting(null);
    }
  };

  const handleSave = async () => {
    if (saving) return;
    setFormError('');
    setNotice('');

    const trimmedKey = apiKey.trim();
    const trimmedModel = modelName.trim();

    if (!providerKey) {
      setFormError('请选择供应商');
      return;
    }
    if (!current?.configured && !trimmedKey) {
      setFormError('首次配置需要填写 API Key');
      return;
    }
    if (!trimmedModel) {
      setFormError('请填写聊天模型名称');
      return;
    }

    setSaving(true);
    try {
      if (current?.configured) {
        // 已配置：Key 留空表示只修改模型
        await userCredentialApi.update(providerKey, {
          apiKey: trimmedKey || undefined,
          modelName: trimmedModel,
        });
      } else {
        await userCredentialApi.save({
          providerKey,
          apiKey: trimmedKey,
          modelName: trimmedModel,
        });
      }
      // 无论成功与否，明文 Key 都不允许继续留在内存里
      setApiKey('');
      setShowApiKey(false);
      setTestResult(null);
      const { credentialList } = await reload();
      const saved = credentialList.find(item => item.providerKey === providerKey);
      setModelName(saved?.modelName ?? trimmedModel);
      setNotice(current?.configured ? '配置已更新' : '配置已保存');
    } catch (error) {
      setFormError(getErrorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!pendingDelete || deleting) return;
    setDeleting(true);
    setFormError('');
    setNotice('');
    try {
      await userCredentialApi.remove(pendingDelete);
      const { credentialList } = await reload();
      setTestResult(null);
      if (pendingDelete === providerKey) {
        const preset = presets.find(item => item.providerKey === providerKey);
        setModelName(preset?.defaultModel ?? '');
        setApiKey('');
      }
      setCredentials(credentialList);
      setNotice('配置已删除，对应的 AI 调用将立即使用新的凭证状态');
    } catch (error) {
      setFormError(getErrorMessage(error));
    } finally {
      setDeleting(false);
      setPendingDelete(null);
    }
  };

  const maskedKey = (credential: UserCredential) => (
    credential.keyTail ? `••••••••${credential.keyTail}` : '••••••••'
  );

  if (loading) {
    return (
      <section className="mb-10 rounded-xl border border-slate-200 bg-white p-8 dark:border-slate-700 dark:bg-slate-800">
        <div className="flex items-center justify-center py-8">
          <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
          <span className="ml-2 text-sm text-slate-500 dark:text-slate-400">加载 AI 配置中…</span>
        </div>
      </section>
    );
  }

  return (
    <section className="mb-10">
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-300">
            <ShieldCheck className="h-4 w-4" />
          </div>
          <div>
            <h2 className="text-lg font-bold text-slate-800 dark:text-white">我的 AI 配置</h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              API Key 仅加密保存在服务端，页面只显示脱敏信息
            </p>
          </div>
        </div>
      </div>

      {loadError && (
        <div className="mb-4 flex items-start gap-2 rounded-xl border border-red-100 bg-red-50 px-3 py-2.5 dark:border-red-900/40 dark:bg-red-900/20">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-500" />
          <span className="text-sm text-red-600 dark:text-red-400">{loadError}</span>
        </div>
      )}

      {/* 能力就绪状态：Chat / 语音 分开展示，未就绪时给出明确引导 */}
      {status && (
        <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <StatusCard
            icon={<MessageSquare className="h-4 w-4" />}
            title="对话模型"
            ready={status.chatReady}
            readyText={`${status.chatProviderKey ?? ''} · ${status.chatModelName ?? ''}`}
            pendingText="未配置，简历分析 / 面试出题 / 评分均不可用"
          />
          <StatusCard
            icon={<Mic className="h-4 w-4" />}
            title="语音（ASR / TTS）"
            ready={status.speechReady}
            readyText={`${status.speechProviderKey ?? ''} · 语音面试可用`}
            pendingText="未配置 DashScope Key，语音面试不可用"
          />
        </div>
      )}

      {!loading && credentials.filter(item => item.configured).length === 0 && (
        <div className="mb-4 flex items-start gap-2 rounded-xl border border-amber-100 bg-amber-50 px-3 py-2.5 dark:border-amber-900/40 dark:bg-amber-900/20">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
          <span className="text-sm text-amber-700 dark:text-amber-300">
            尚未配置任何 AI 凭证。配置后，平台所有 AI 功能都会使用你自己的模型与额度；
            未配置时请求会被明确拒绝，不会使用平台默认凭证。
          </span>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* 配置表单 */}
        <div className="flex flex-col rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <h3 className="mb-4 text-sm font-semibold text-slate-800 dark:text-white">
            {current?.configured ? '修改配置' : '新增配置'}
          </h3>

          <label htmlFor="my-ai-provider" className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">
            供应商
          </label>
          <select
            id="my-ai-provider"
            value={providerKey}
            onChange={(event) => handleProviderChange(event.target.value)}
            disabled={saving || presets.length === 0}
            className="mb-4 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 transition focus:border-primary-400 focus:outline-none focus:ring-2 focus:ring-primary-500/40 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
          >
            {presets.length === 0 && <option value="">暂无可选供应商</option>}
            {presets.map(preset => (
              <option key={preset.providerKey} value={preset.providerKey}>
                {preset.displayName}
                {preset.configured ? '（已配置）' : ''}
              </option>
            ))}
          </select>

          <label htmlFor="my-ai-model" className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">
            聊天模型
          </label>
          <input
            id="my-ai-model"
            type="text"
            value={modelName}
            onChange={(event) => setModelName(event.target.value)}
            disabled={saving}
            placeholder={currentPreset?.defaultModel ?? '例如 qwen-plus'}
            className="mb-1 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 transition focus:border-primary-400 focus:outline-none focus:ring-2 focus:ring-primary-500/40 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
          />
          <p className="mb-4 text-[11px] text-slate-400 dark:text-slate-500">
            Base URL 由服务端白名单固定，不可修改
          </p>

          <label htmlFor="my-ai-key" className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">
            API Key
          </label>
          <div className="relative">
            <KeyRound className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              id="my-ai-key"
              type={showApiKey ? 'text' : 'password'}
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
              disabled={saving}
              autoComplete="off"
              spellCheck={false}
              placeholder={current?.configured ? `已配置 ${maskedKey(current)}，留空则保持不变` : '请输入 API Key'}
              className="w-full rounded-lg border border-slate-200 bg-white py-2 pl-10 pr-10 text-sm text-slate-800 transition focus:border-primary-400 focus:outline-none focus:ring-2 focus:ring-primary-500/40 disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
            />
            <button
              type="button"
              onClick={() => setShowApiKey(value => !value)}
              aria-label={showApiKey ? '隐藏 API Key' : '显示 API Key'}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
            >
              {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
          <p className="mt-1 text-[11px] text-slate-400 dark:text-slate-500">
            Key 只保存在当前页面内存中，保存成功后立即清空，不会写入浏览器存储
          </p>

          {formError && (
            <div className="mt-3 flex items-start gap-2 rounded-lg border border-red-100 bg-red-50 px-3 py-2 dark:border-red-900/40 dark:bg-red-900/20">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-500" />
              <span className="text-xs text-red-600 dark:text-red-400">{formError}</span>
            </div>
          )}

          {notice && (
            <div className="mt-3 flex items-start gap-2 rounded-lg border border-emerald-100 bg-emerald-50 px-3 py-2 dark:border-emerald-900/40 dark:bg-emerald-900/20">
              <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
              <span className="text-xs text-emerald-600 dark:text-emerald-400">{notice}</span>
            </div>
          )}

          {testResult && (
            <div
              className={`mt-3 flex items-start gap-2 rounded-lg border px-3 py-2 ${
                testResult.success
                  ? 'border-emerald-100 bg-emerald-50 dark:border-emerald-900/40 dark:bg-emerald-900/20'
                  : 'border-red-100 bg-red-50 dark:border-red-900/40 dark:bg-red-900/20'
              }`}
            >
              {testResult.success
                ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
                : <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-500" />}
              <div className="min-w-0">
                <p className={`text-xs font-medium ${
                  testResult.success ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'
                }`}>
                  {testResult.success ? '连接成功' : '连接失败'}
                  {testResult.success && testResult.latencyMs > 0 ? `（${testResult.latencyMs} ms）` : ''}
                </p>
                <p className="mt-0.5 break-words text-[11px] text-slate-500 dark:text-slate-400">
                  {testResult.message}
                </p>
                <p className="mt-0.5 text-[11px] text-slate-400 dark:text-slate-500">
                  模型：{testResult.modelName || '未设置'}
                </p>
              </div>
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-4 dark:border-slate-700">
            <button
              type="button"
              onClick={handleSave}
              disabled={saving || presets.length === 0}
              className="inline-flex h-9 items-center gap-2 rounded-lg bg-gradient-to-r from-neon-purple via-primary-500 to-neon-blue px-4 text-xs font-semibold text-white shadow-sm transition hover:from-primary-600 hover:to-primary-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              {current?.configured ? '更新配置' : '保存配置'}
            </button>
            <button
              type="button"
              onClick={() => handleTest('form')}
              disabled={testing !== null || saving || presets.length === 0}
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-slate-200 px-4 text-xs font-medium text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-900/40"
            >
              {testing === 'form'
                ? <Loader2 className="h-4 w-4 animate-spin" />
                : <PlugZap className="h-4 w-4" />}
              {testing === 'form' ? '测试中…' : '测试连接'}
            </button>
            {current?.configured && (
              <button
                type="button"
                onClick={() => setPendingDelete(providerKey)}
                disabled={saving || deleting}
                className="inline-flex h-9 items-center gap-2 rounded-lg border border-red-200 px-4 text-xs font-medium text-red-600 transition hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-red-900/50 dark:text-red-400 dark:hover:bg-red-900/20"
              >
                <Trash2 className="h-4 w-4" />
                删除
              </button>
            )}
          </div>
        </div>

        {/* 已配置供应商列表 */}
        <div className="flex flex-col rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800">
          <h3 className="mb-4 text-sm font-semibold text-slate-800 dark:text-white">
            已配置供应商
            <span className="ml-2 text-xs font-normal text-slate-400 dark:text-slate-500">
              {credentials.filter(item => item.configured).length} 个
            </span>
          </h3>

          {credentials.filter(item => item.configured).length === 0 ? (
            <div className="flex flex-1 flex-col items-center justify-center rounded-lg border border-dashed border-slate-200 py-10 dark:border-slate-700">
              <KeyRound className="mb-2 h-8 w-8 text-slate-300 dark:text-slate-600" />
              <p className="text-xs text-slate-500 dark:text-slate-400">尚未配置任何 AI 供应商</p>
            </div>
          ) : (
            <ul className="space-y-3">
              {credentials.filter(item => item.configured).map(credential => {
                const preset = presets.find(item => item.providerKey === credential.providerKey);
                return (
                  <li
                    key={credential.providerKey}
                    className={`rounded-lg border p-3 transition ${
                      credential.providerKey === providerKey
                        ? 'border-primary-200 bg-primary-50/60 dark:border-primary-900/50 dark:bg-primary-900/20'
                        : 'border-slate-100 bg-slate-50/70 dark:border-slate-700/80 dark:bg-slate-900/30'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-slate-800 dark:text-white">
                          {preset?.displayName ?? credential.providerKey}
                        </p>
                        <p className="mt-0.5 truncate text-xs text-slate-500 dark:text-slate-400">
                          模型：{credential.modelName ?? '未设置'}
                        </p>
                        <p className="mt-0.5 font-mono text-xs text-slate-500 dark:text-slate-400">
                          API Key：{maskedKey(credential)}
                        </p>
                        {credential.updatedAt && (
                          <p className="mt-0.5 text-[11px] text-slate-400 dark:text-slate-500">
                            更新于 {new Date(credential.updatedAt).toLocaleString()}
                          </p>
                        )}
                      </div>
                      <div className="flex shrink-0 items-center gap-1">
                        <button
                          type="button"
                          onClick={() => handleTest(credential.providerKey)}
                          disabled={testing !== null}
                          aria-label={`测试 ${credential.providerKey} 连接`}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-slate-500 transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-50 dark:text-slate-400 dark:hover:bg-slate-800"
                        >
                          {testing === credential.providerKey ? '测试中…' : '测试'}
                        </button>
                        <button
                          type="button"
                          onClick={() => handleProviderChange(credential.providerKey)}
                          className="rounded-lg px-2 py-1 text-xs font-medium text-primary-600 transition hover:bg-primary-100/60 dark:text-primary-300 dark:hover:bg-primary-900/30"
                        >
                          编辑
                        </button>
                        <button
                          type="button"
                          onClick={() => setPendingDelete(credential.providerKey)}
                          aria-label={`删除 ${credential.providerKey} 配置`}
                          className="rounded-lg p-1.5 text-slate-400 transition hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={pendingDelete !== null}
        title="删除 AI 配置"
        message={`确认删除「${presets.find(item => item.providerKey === pendingDelete)?.displayName
          ?? pendingDelete ?? ''}」的配置？删除后该供应商的 API Key 将从服务端移除。`}
        confirmText="删除"
        confirmVariant="danger"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </section>
  );
}

/** 单项 AI 能力就绪状态卡片 */
function StatusCard({
  icon,
  title,
  ready,
  readyText,
  pendingText,
}: {
  icon: ReactNode;
  title: string;
  ready: boolean;
  readyText: string;
  pendingText: string;
}) {
  return (
    <div
      className={`rounded-xl border p-3 ${
        ready
          ? 'border-emerald-100 bg-emerald-50/70 dark:border-emerald-900/40 dark:bg-emerald-900/15'
          : 'border-slate-200 bg-slate-50/70 dark:border-slate-700 dark:bg-slate-900/30'
      }`}
    >
      <div className="flex items-center gap-2">
        <span className={ready ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500'}>
          {icon}
        </span>
        <span className="text-xs font-semibold text-slate-700 dark:text-slate-200">{title}</span>
        <span
          className={`ml-auto inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium ${
            ready
              ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
              : 'bg-slate-200 text-slate-600 dark:bg-slate-700 dark:text-slate-300'
          }`}
        >
          {ready ? '已就绪' : '未配置'}
        </span>
      </div>
      <p className="mt-2 break-words text-[11px] leading-relaxed text-slate-500 dark:text-slate-400">
        {ready ? readyText : pendingText}
      </p>
    </div>
  );
}
