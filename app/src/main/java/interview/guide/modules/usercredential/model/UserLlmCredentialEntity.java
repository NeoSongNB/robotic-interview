package interview.guide.modules.usercredential.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户级 LLM 凭证。
 *
 * <p>安全约定：</p>
 * <ul>
 *   <li>数据库只保存 AES-256-GCM 密文与 nonce，任何情况下不落明文。</li>
 *   <li>{@code keyTail} 仅保存明文末尾少量字符，用于前端脱敏展示。</li>
 *   <li>Base URL 不落库、不由前端传入，运行时由服务端白名单解析，
 *       从根本上消除 SSRF 风险。</li>
 *   <li>该实体不会被序列化到 Redis / Redis Stream / 日志 / 工作流状态。</li>
 * </ul>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "user_llm_credential",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_user_llm_credential_user_provider",
            columnNames = {"user_id", "provider_key"})
    }
)
public class UserLlmCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "provider_key", nullable = false, length = 64)
    private String providerKey;

    /** 用户选择的 Chat 模型名称（自由文本，但受服务端字符集与长度约束）。 */
    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "api_key_ciphertext", nullable = false, length = 4096)
    private String apiKeyCiphertext;

    /** Base64 编码的 12 字节 GCM nonce。 */
    @Column(name = "api_key_nonce", nullable = false, length = 64)
    private String apiKeyNonce;

    /** 加密主密钥版本，为将来轮换预留。 */
    @Column(name = "key_version", nullable = false)
    private Integer keyVersion;

    /** 用户凭证配置版本，每次写操作递增，便于调用侧判断配置是否变更。 */
    @Column(name = "credential_version", nullable = false)
    private Long credentialVersion;

    /** 明文末尾 4 位，仅用于脱敏展示。 */
    @Column(name = "key_tail", length = 8)
    private String keyTail;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 该凭证是否可用于对话模型调用（阶段二）。 */
    @Column(name = "chat_enabled", nullable = false)
    private Boolean chatEnabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.keyVersion == null) {
            this.keyVersion = 1;
        }
        if (this.credentialVersion == null) {
            this.credentialVersion = 1L;
        }
        if (this.status == null) {
            this.status = CredentialStatus.ACTIVE.name();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
