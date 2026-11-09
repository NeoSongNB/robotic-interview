import request from './request';
import type {
  CredentialStatus,
  CredentialTestResult,
  ProviderPreset,
  SaveCredentialPayload,
  TestCredentialPayload,
  UpdateCredentialPayload,
  UserCredential,
} from '../types/userCredential';

/**
 * 当前用户的 AI 模型凭证接口。
 *
 * 所有请求的 userId 都由后端从认证上下文推导，前端不传、也无法传。
 */
export const userCredentialApi = {
  list(): Promise<UserCredential[]> {
    return request.get<UserCredential[]>('/api/me/ai-credentials');
  },

  providers(): Promise<ProviderPreset[]> {
    return request.get<ProviderPreset[]>('/api/me/ai-credentials/providers');
  },

  /** Chat / 语音能力就绪状态 */
  status(): Promise<CredentialStatus> {
    return request.get<CredentialStatus>('/api/me/ai-credentials/status');
  },

  detail(providerKey: string): Promise<UserCredential> {
    return request.get<UserCredential>(`/api/me/ai-credentials/${providerKey}`);
  },

  save(payload: SaveCredentialPayload): Promise<UserCredential> {
    return request.post<UserCredential>('/api/me/ai-credentials', payload);
  },

  update(providerKey: string, payload: UpdateCredentialPayload): Promise<UserCredential> {
    return request.put<UserCredential>(`/api/me/ai-credentials/${providerKey}`, payload);
  },

  remove(providerKey: string): Promise<void> {
    return request.delete<void>(`/api/me/ai-credentials/${providerKey}`);
  },

  /** 使用已保存的凭证做真实连接测试（后端会实际请求供应商） */
  test(providerKey: string): Promise<CredentialTestResult> {
    return request.post<CredentialTestResult>(`/api/me/ai-credentials/${providerKey}/test`);
  },

  /** 使用未保存的临时凭证做连接测试（不落库） */
  testTransient(payload: TestCredentialPayload): Promise<CredentialTestResult> {
    return request.post<CredentialTestResult>('/api/me/ai-credentials/test', payload);
  },
};

export default userCredentialApi;
