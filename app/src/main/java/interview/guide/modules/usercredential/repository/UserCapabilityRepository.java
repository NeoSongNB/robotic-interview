package interview.guide.modules.usercredential.repository;

import interview.guide.modules.usercredential.model.AiCapability;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserCapabilityRepository {
  private final JdbcClient jdbc;

  public record Selection(String providerKey, Long credentialId, String modelName) {}

  public Optional<Selection> find(Long userId, AiCapability capability) {
    return jdbc.sql("SELECT provider_key, credential_id, model_name FROM user_ai_capability WHERE user_id = :userId AND capability = :capability")
        .param("userId", userId).param("capability", capability.name()).query((rs, row) -> new Selection(rs.getString(1), rs.getLong(2), rs.getString(3))).optional();
  }

  public void save(Long userId, AiCapability capability, Selection selected) {
    jdbc.sql("INSERT INTO user_ai_capability (user_id, capability, provider_key, credential_id, model_name) "
        + "VALUES (:userId, :capability, :provider, :credentialId, :model) "
        + "ON CONFLICT (user_id, capability) DO UPDATE SET provider_key = EXCLUDED.provider_key, credential_id = EXCLUDED.credential_id, model_name = EXCLUDED.model_name")
        .param("userId", userId).param("capability", capability.name())
        .param("provider", selected.providerKey()).param("credentialId", selected.credentialId())
        .param("model", selected.modelName()).update();
  }

  public void clear(Long userId, AiCapability capability) {
    jdbc.sql("DELETE FROM user_ai_capability WHERE user_id = :userId AND capability = :capability")
        .param("userId", userId).param("capability", capability.name()).update();
  }
}
