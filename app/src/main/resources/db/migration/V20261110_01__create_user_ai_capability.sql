CREATE TABLE user_ai_capability (
  user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  capability VARCHAR(8) NOT NULL CHECK (capability IN ('TEXT', 'ASR', 'TTS')),
  provider_key VARCHAR(64) NOT NULL,
  credential_id BIGINT NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  PRIMARY KEY (user_id, capability)
);

ALTER TABLE interview_sessions ALTER COLUMN llm_provider TYPE VARCHAR(128);
ALTER TABLE voice_interview_sessions ALTER COLUMN llm_provider TYPE VARCHAR(128);
ALTER TABLE voice_interview_sessions ADD COLUMN asr_reference VARCHAR(128);
ALTER TABLE voice_interview_sessions ADD COLUMN tts_reference VARCHAR(128);
ALTER TABLE resumes ADD COLUMN text_model_reference VARCHAR(128);
