export interface ProviderItem {
  id: string;
  baseUrl: string;
  maskedApiKey: string;
  model: string;
  temperature: number | null;
  defaultChatProvider: boolean;
}

export interface CreateProviderRequest {
  id: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  temperature?: number;
}

export interface UpdateProviderRequest {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  temperature?: number;
}

export interface ProviderTestResult {
  success: boolean;
  message: string;
  model: string;
}

export interface DefaultProvider {
  defaultProvider: string;
}

export interface AsrConfig {
  url: string;
  model: string;
  maskedApiKey: string;
  language: string;
  format: string;
  sampleRate: number;
  enableTurnDetection: boolean;
  turnDetectionType: string;
  turnDetectionThreshold: number;
  turnDetectionSilenceDurationMs: number;
}

export interface TtsConfig {
  model: string;
  maskedApiKey: string;
  voice: string;
  format: string;
  sampleRate: number;
  mode: string;
  languageType: string;
  speechRate: number;
  volume: number;
}

export interface AsrConfigRequest {
  url?: string;
  model?: string;
  apiKey?: string;
  language?: string;
  format?: string;
  sampleRate?: number;
  enableTurnDetection?: boolean;
  turnDetectionType?: string;
  turnDetectionThreshold?: number;
  turnDetectionSilenceDurationMs?: number;
}

export interface TtsConfigRequest {
  model?: string;
  apiKey?: string;
  voice?: string;
  format?: string;
  sampleRate?: number;
  mode?: string;
  languageType?: string;
  speechRate?: number;
  volume?: number;
}
