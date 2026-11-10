package interview.guide.modules.usercredential.dto;

import interview.guide.modules.usercredential.model.AiCapability;
import java.util.List;

public record CapabilitySelectionDTO(AiCapability capability, String providerKey, boolean ready, List<Option> options) {
  public record Option(String providerKey, String displayName, String modelName) {}
}
