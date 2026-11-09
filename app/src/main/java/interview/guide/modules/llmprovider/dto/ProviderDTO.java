package interview.guide.modules.llmprovider.dto;

import lombok.Builder;

@Builder
public record ProviderDTO(
    String id,
    String baseUrl,
    String maskedApiKey,
    String model,
    Double temperature,
    boolean defaultChatProvider
) {}
