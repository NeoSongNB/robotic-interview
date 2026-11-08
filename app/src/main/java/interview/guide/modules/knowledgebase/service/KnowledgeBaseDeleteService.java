package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库删除服务
 * 负责知识库的删除操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDeleteService {
    
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatSessionRepository sessionRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final FileStorageService storageService;
    private final TransactionalExecutor transactionalExecutor;
    private final CurrentUserProvider currentUserProvider;
    
    /**
     * 删除知识库
     * 包括：RAG会话关联、向量数据、RustFS文件、数据库记录
     */
    public void deleteKnowledgeBase(Long id) {
        String storageKey = transactionalExecutor.call(() -> deleteKnowledgeBaseRecords(id));

        vectorService.deleteByKnowledgeBaseId(id);

        try {
            storageService.deleteKnowledgeBase(storageKey);
        } catch (Exception e) {
            log.warn(
                "知识库数据库记录已删除，但RustFS文件清理失败，可后续按storageKey补偿: kbId={}, storageKey={}, error={}",
                id, storageKey, e.getMessage(), e
            );
        }

        log.info("知识库已删除: id={}", id);
    }

    /**
     * 批量删除：一次 HTTP 请求内完成，避免前端循环调用撞上 2~10 次/秒的限流。
     * 每条仍走 deleteKnowledgeBase 的完整归属校验与清理链路；单条失败不影响其它条目。
     */
    public Map<String, Object> deleteKnowledgeBases(List<Long> ids) {
        List<Long> safeIds = ids == null ? List.of() : ids;
        int deleted = 0;
        List<Long> failed = new ArrayList<>();
        for (Long id : safeIds) {
            if (id == null) {
                continue;
            }
            try {
                deleteKnowledgeBase(id);
                deleted++;
            } catch (Exception e) {
                failed.add(id);
                log.warn("批量删除中的单条失败: kbId={}, error={}", id,
                    interview.guide.common.log.ErrorLogSanitizer.summarize(e));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("deleted", deleted);
        result.put("failed", failed.size());
        result.put("failedIds", failed);
        return result;
    }

    private String deleteKnowledgeBaseRecords(Long id) {
        // 1. 获取知识库信息（带归属校验：跨用户删除按"不存在"处理）
        // 与查询接口保持同一错误码（KNOWLEDGE_BASE_NOT_FOUND），避免同一语义出现两套 code
        KnowledgeBaseEntity kb = knowledgeBaseRepository
            .findByIdAndUserId(id, currentUserProvider.requireUserId())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        String storageKey = kb.getStorageKey();
        
        // 2. 删除所有RAG会话中的知识库关联（必须先删除关联，否则外键约束会阻止删除）
        List<RagChatSessionEntity> sessions = sessionRepository.findByKnowledgeBaseIds(List.of(id));
        for (RagChatSessionEntity session : sessions) {
            session.getKnowledgeBases().removeIf(kbEntity -> kbEntity.getId().equals(id));
            sessionRepository.save(session);
            log.debug("已从会话中移除知识库关联: sessionId={}, kbId={}", session.getId(), id);
        }
        if (!sessions.isEmpty()) {
            log.info("已从 {} 个会话中移除知识库关联: kbId={}", sessions.size(), id);
        }
        
        // 3. 删除知识库记录（在事务中）
        knowledgeBaseRepository.deleteById(id);
        return storageKey;
    }
}
