package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * BYOK（用户自带 Key）相关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.byok")
public class ByokProperties {

    /**
     * 允许用户自带凭证的供应商白名单。
     *
     * <p>只有出现在该列表中的 {@code providerKey} 才允许被用户配置；
     * 其 Base URL 由服务端从 {@code app.ai.providers.<key>.base-url} 读取，
     * 前端无法指定，避免 SSRF。</p>
     */
    private List<String> allowedProviders = List.of("dashscope", "kimi", "deepseek", "glm");

    /** 用户 API Key 允许的最大长度，超出直接拒绝，避免超长内容撑爆存储。 */
    private int maxApiKeyLength = 512;

    /** Chat 模型名称允许的最大长度。 */
    private int maxModelNameLength = 128;

    /** 用户级模型客户端缓存配置。 */
    private Cache cache = new Cache();

    @Data
    public static class Cache {

        /**
         * 单个缓存（ChatClient / ChatModel）的最大条目数。
         *
         * <p>超限后按写入顺序淘汰最旧条目，避免用户规模增长导致堆内存无界增长。</p>
         */
        private int maxEntries = 512;

        /** 条目存活时间；过期后下次访问重新构建客户端（会重新读取并解密凭证）。 */
        private Duration ttl = Duration.ofMinutes(30);
    }
}
