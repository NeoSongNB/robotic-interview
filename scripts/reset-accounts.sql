-- ============================================================
-- 清空所有"已注册账户"及其全部用户级数据（登录测验用）
-- 安全约定：
--   1. 保留特殊账户 system（DISABLED，无法登录，FK 归属历史数据所必须）。
--   2. 保留平台级配置 llm_provider_config / llm_global_setting（非账户数据）。
--   3. 按 FK 依赖顺序删除，避免 RESTRICT 冲突。
-- ============================================================

DO $$
DECLARE
  sys_id BIGINT;
BEGIN
  SELECT id INTO sys_id FROM app_user WHERE username = 'system';

  -- 1) 面试作答 -> 面试会话
  DELETE FROM interview_answers
   WHERE session_id IN (SELECT id FROM interview_sessions WHERE user_id <> sys_id);
  DELETE FROM interview_sessions WHERE user_id <> sys_id;

  -- 2) 简历分析 -> 简历
  DELETE FROM resume_analyses
   WHERE resume_id IN (SELECT id FROM resumes WHERE user_id <> sys_id);
  DELETE FROM resumes WHERE user_id <> sys_id;

  -- 3) 语音面试消息 / 评测 -> 语音会话（这两张表无 FK，按 session_id 清理避免孤儿行）
  DELETE FROM voice_interview_messages
   WHERE session_id IN (SELECT id FROM voice_interview_sessions WHERE user_id <> sys_id);
  DELETE FROM voice_interview_evaluations
   WHERE session_id IN (SELECT id FROM voice_interview_sessions WHERE user_id <> sys_id);
  DELETE FROM voice_interview_sessions WHERE user_id <> sys_id;

  -- 4) 用户级 LLM 凭证（API Key）
  DELETE FROM user_llm_credential WHERE user_id <> sys_id;

  -- 5) 最后删除非 system 账户
  DELETE FROM app_user WHERE id <> sys_id;
END $$;
