package interview.guide.modules.usercredential.model;

public enum AiCapability {
  TEXT("文本生成"), ASR("语音转写"), TTS("语音播报");

  private final String label;

  AiCapability(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
