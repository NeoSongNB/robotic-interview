package interview.guide.modules.usercredential.dto;

import jakarta.validation.constraints.Size;

public record SelectCapabilityRequest(@Size(max = 64) String providerKey) {}
