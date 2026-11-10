package interview.guide.modules.usercredential.service;

import interview.guide.modules.usercredential.model.AiCapability;
import java.util.Map;

public final class SpeechPresets {
  public record Preset(AiCapability capability, String name, String endpoint, String model) {}

  public static final Map<String, Preset> ALL = Map.of(
      "dashscope-asr", new Preset(AiCapability.ASR, "百炼语音转写",
          "wss://dashscope.aliyuncs.com/api-ws/v1/realtime", "qwen3-asr-flash-realtime"),
      "dashscope-tts", new Preset(AiCapability.TTS, "百炼语音播报",
          "wss://dashscope.aliyuncs.com/api-ws/v1/realtime", "qwen3-tts-flash-realtime"),
      "volcengine-asr", new Preset(AiCapability.ASR, "火山引擎语音转写",
          "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async", "volc.seedasr.sauc.duration"),
      "volcengine-tts", new Preset(AiCapability.TTS, "火山引擎语音播报",
          "https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse", "seed-tts-2.0"));

  private SpeechPresets() {}
}
