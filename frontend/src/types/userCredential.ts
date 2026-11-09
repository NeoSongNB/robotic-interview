/**
 * 用户级 AI 凭证类型。
 *
 * 安全约定：后端只返回脱敏信息，前端永远拿不到完整 API Key，
 * 前端也不得把 API Key 写入 localStorage / sessionStorage / 任何持久化位置。
 */

export interface UserCredential {
  providerKey: string;
  configured: boolean;
  modelName: string | null;
  /** 明文末尾 4 位，仅用于展示 */
  keyTail: string | null;
  updatedAt: string | null;
  /** 该凭证可用于对话模型调用 */
  chatEnabled?: boolean;
}

export interface ProviderPreset {
  providerKey: string;
  displayName: string;
  /** 服务端下发，只读展示，前端不可编辑也不提交 */
  baseUrl: string;
  defaultModel: string | null;
  configured: boolean;
}

/** 当前用户 AI 能力就绪状态 */
export interface CredentialStatus {
  chatReady: boolean;
  chatProviderKey: string | null;
  chatModelName: string | null;
  speechReady: boolean;
  speechProviderKey: string | null;
}

/** 连接测试结果（错误信息已由后端脱敏） */
export interface CredentialTestResult {
  success: boolean;
  providerKey: string;
  modelName: string;
  latencyMs: number;
  message: string;
  errorCode: string | null;
}

export interface SaveCredentialPayload {
  providerKey: string;
  apiKey: string;
  modelName: string;
}

export interface UpdateCredentialPayload {
  /** 留空表示保持原 Key 不变 */
  apiKey?: string;
  modelName?: string;
}

export interface TestCredentialPayload {
  providerKey: string;
  apiKey: string;
  modelName?: string;
}

/** 凭证相关业务错误码（与后端 ErrorCode 保持一致） */
export const CREDENTIAL_ERROR_CODES = {
  NOT_CONFIGURED: 13001,
  INVALID: 13002,
  PROVIDER_NOT_ALLOWED: 13003,
  BASEURL_NOT_ALLOWED: 13004,
  DECRYPTION_FAILED: 13006,
  MODEL_NOT_ALLOWED: 13008,
  PROVIDER_AUTHENTICATION_FAILED: 13009,
  TEST_FAILED: 13010,
  AI_CONTEXT_USER_UNAVAILABLE: 13011,
} as const;
