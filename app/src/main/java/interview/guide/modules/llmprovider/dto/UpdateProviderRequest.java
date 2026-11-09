package interview.guide.modules.llmprovider.dto;

public record UpdateProviderRequest(
    String baseUrl,
    String apiKey,
    String model,
    Double temperature
) {
}
