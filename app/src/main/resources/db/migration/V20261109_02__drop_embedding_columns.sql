-- ============================================================
-- 下线 Embedding / 向量模型元数据。
-- 知识库与 RAG 相关表已由 V20261109_01 删除，业务链路不再读取这些列，
-- 因此随代码清理一并移除；历史迁移中的 vector 扩展仍保留，供空库回放。
-- ============================================================

ALTER TABLE llm_provider_config
  DROP COLUMN IF EXISTS embedding_model,
  DROP COLUMN IF EXISTS embedding_dimensions,
  DROP COLUMN IF EXISTS supports_embedding;

ALTER TABLE llm_global_setting
  DROP COLUMN IF EXISTS default_embedding_provider_id;

DROP INDEX IF EXISTS idx_user_llm_credential_embedding;

ALTER TABLE user_llm_credential
  DROP COLUMN IF EXISTS embedding_enabled;
