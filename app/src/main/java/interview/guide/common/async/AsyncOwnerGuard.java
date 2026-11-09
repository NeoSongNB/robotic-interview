package interview.guide.common.async;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 异步任务「用户归属」守卫。
 *
 * <p>BYOK 阶段二要求：每个 AI 异步任务都必须能确定所属用户，且
 * <b>绝不能</b>因为异步线程丢失 SecurityContext 就回退到开发者全局 Key。</p>
 *
 * <p>因此 Redis Stream 消息里<b>不放</b> userId、apiKey、ProviderSnapshot 或模型客户端对象，
 * Worker 一律通过业务资源（简历 / 会话）的 {@code user_id} 反查所有者。
 * 本守卫负责在反查结果为空时立即失败，而不是让空 userId 继续向下传递。</p>
 */
@Slf4j
public final class AsyncOwnerGuard {

    private AsyncOwnerGuard() {
    }

    /**
     * 断言异步任务能确定资源所有者。
     *
     * @param ownerUserId  从数据库资源反查到的所有者 ID
     * @param resourceKind 资源类型（日志用）
     * @param resourceId   资源 ID（日志用）
     * @return 非空的 ownerUserId
     * @throws BusinessException 所有者为空时抛出，杜绝"无主任务用平台 Key 跑"
     */
    public static Long requireOwner(Long ownerUserId, String resourceKind, Object resourceId) {
        if (ownerUserId == null) {
            log.error("异步任务无法确定资源所有者，已拒绝执行（禁止回退平台凭证）: resource={}, id={}",
                resourceKind, resourceId);
            throw new BusinessException(ErrorCode.AI_CONTEXT_USER_UNAVAILABLE);
        }
        return ownerUserId;
    }

    /**
     * 如果消息里带了 userId，则必须与数据库中的资源所有者一致。
     *
     * <p>当前设计不把 userId 放进消息；该方法是防御性校验，
     * 用于将来消息结构扩展时防止伪造/越权。</p>
     */
    public static void verifyConsistent(Long messageUserId, Long ownerUserId, String resourceKind) {
        if (messageUserId == null) {
            return;
        }
        if (!messageUserId.equals(ownerUserId)) {
            log.error("异步消息 userId 与资源所有者不一致，已拒绝执行: resource={}, messageUserId={}, ownerUserId={}",
                resourceKind, messageUserId, ownerUserId);
            throw new BusinessException(ErrorCode.AI_CONTEXT_USER_UNAVAILABLE);
        }
    }
}
