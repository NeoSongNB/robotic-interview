package interview.guide.modules.usercredential.repository;

import interview.guide.modules.usercredential.model.UserLlmCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户凭证仓储。
 *
 * <p><b>约定</b>：所有查询都必须带 {@code userId}，禁止仅按 {@code providerKey} 查询，
 * 否则会跨越用户边界读取他人凭证。</p>
 */
@Repository
public interface UserLlmCredentialRepository extends JpaRepository<UserLlmCredentialEntity, Long> {

    List<UserLlmCredentialEntity> findAllByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<UserLlmCredentialEntity> findByUserIdAndProviderKey(Long userId, String providerKey);

    /**
     * 查询用户可用于 Chat 的凭证（按更新时间倒序，第一个即「主 Chat 凭证」）。
     */
    List<UserLlmCredentialEntity> findByUserIdAndChatEnabledTrueOrderByUpdatedAtDesc(Long userId);

    void deleteByUserIdAndProviderKey(Long userId, String providerKey);

    boolean existsByUserIdAndProviderKey(Long userId, String providerKey);
}
