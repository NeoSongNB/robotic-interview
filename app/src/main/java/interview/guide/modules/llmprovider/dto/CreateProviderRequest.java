package interview.guide.modules.llmprovider.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProviderRequest(
    @NotBlank String id,
    @NotBlank String baseUrl,
    @NotBlank String apiKey,
    @NotBlank String model,
    Double temperature
) {
}
