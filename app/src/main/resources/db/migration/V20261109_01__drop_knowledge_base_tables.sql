-- 下线知识库模块：清理仅知识库使用的 6 张表及其索引/约束。
-- 历史迁移保持原样；全部使用 IF EXISTS，保证已有库与全新库（V1/V20260723 先建表）都能回放。
-- 先显式解除跨表/跨模块外键，再按子表 -> 父表顺序删表，避免 RESTRICT 冲突。
-- interview_sessions / app_user / llm_* / user_llm_credential 等共用表保留。

ALTER TABLE rag_session_knowledge_bases
    DROP CONSTRAINT IF EXISTS fkqfob368wcvb82elsjkx2troqu,
    DROP CONSTRAINT IF EXISTS fkjsfwqyt1ntgr0fcvjieq8c0nb;

ALTER TABLE rag_chat_messages
    DROP CONSTRAINT IF EXISTS fkfohypaygc0qfqo62vyaxlbntn;

ALTER TABLE rag_chat_sessions
    DROP CONSTRAINT IF EXISTS fk_rag_chat_sessions_user;

ALTER TABLE knowledge_base_questions
    DROP CONSTRAINT IF EXISTS fkosobqu06r3tbr13ca043slftw;

ALTER TABLE knowledge_bases
    DROP CONSTRAINT IF EXISTS fk_knowledge_bases_user;

DROP TABLE IF EXISTS rag_session_knowledge_bases;
DROP TABLE IF EXISTS rag_chat_messages;
DROP TABLE IF EXISTS rag_chat_sessions;
DROP TABLE IF EXISTS knowledge_base_questions;
DROP TABLE IF EXISTS vector_store;
DROP TABLE IF EXISTS knowledge_bases;
