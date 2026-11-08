package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 获取知识库列表（支持状态过滤和排序）
     * 
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        // 列表自动过滤为当前用户的知识库
        Long userId = currentUserProvider.requireUserId();
        List<KnowledgeBaseEntity> entities;

        // 如果指定了状态，按状态过滤
        if (vectorStatus != null) {
            entities = knowledgeBaseRepository
                .findByUserIdAndVectorStatusOrderByUploadedAtDesc(userId, vectorStatus);
        } else {
            entities = knowledgeBaseRepository.findAllByUserIdOrderByUploadedAtDesc(userId);
        }
        
        // 如果指定了排序字段，在内存中排序
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }
        
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    /**
     * 根据ID获取知识库详情
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return findOwned(id).map(knowledgeBaseMapper::toListItemDTO);
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作，带归属校验）。
     */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return findOwned(id);
    }

    /**
     * 取出知识库并强制校验归属；跨用户访问抛 {@code KNOWLEDGE_BASE_NOT_FOUND}（404）。
     */
    public KnowledgeBaseEntity requireOwned(Long id) {
        return findOwned(id)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }

    private Optional<KnowledgeBaseEntity> findOwned(Long id) {
        return knowledgeBaseRepository.findByIdAndUserId(
            id, currentUserProvider.requireUserId());
    }

    /**
     * 根据ID列表获取知识库名称列表（只返回当前用户可见的知识库）。
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        Long userId = currentUserProvider.requireUserId();
        return ids.stream()
            .map(id -> knowledgeBaseRepository.findByIdAndUserId(id, userId)
                .map(KnowledgeBaseEntity::getName)
                .orElse("未知知识库"))
            .toList();
    }

    // ========== 分类管理 ==========

    /**
     * 获取所有分类
     */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategoriesByUserId(
            currentUserProvider.requireUserId());
    }

    /**
     * 根据分类获取知识库列表（限定当前用户）
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        Long userId = currentUserProvider.requireUserId();
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository
                .findByUserIdAndCategoryIsNullOrderByUploadedAtDesc(userId);
        } else {
            entities = knowledgeBaseRepository
                .findByUserIdAndCategoryOrderByUploadedAtDesc(userId, category);
        }
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 更新知识库分类（带归属校验）
     */
    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = requireOwned(id);
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    /** 重命名分类：只影响当前用户名下该分类的知识库，返回受影响的条数。 */
    @Transactional
    public int renameCategory(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原分类与新分类都不能为空");
        }
        if (from.equals(to)) {
            return 0;
        }
        List<KnowledgeBaseEntity> entities = knowledgeBaseRepository
            .findByUserIdAndCategoryOrderByUploadedAtDesc(currentUserProvider.requireUserId(), from);
        for (KnowledgeBaseEntity entity : entities) {
            entity.setCategory(to);
        }
        knowledgeBaseRepository.saveAll(entities);
        log.info("分类已重命名: from={}, to={}, affected={}", from, to, entities.size());
        return entities.size();
    }

    /** 删除分类：把该分类下的知识库置为未分类，不删除文件本身。 */
    @Transactional
    public int removeCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类名不能为空");
        }
        List<KnowledgeBaseEntity> entities = knowledgeBaseRepository
            .findByUserIdAndCategoryOrderByUploadedAtDesc(currentUserProvider.requireUserId(), category);
        for (KnowledgeBaseEntity entity : entities) {
            entity.setCategory(null);
        }
        knowledgeBaseRepository.saveAll(entities);
        log.info("分类已移除: category={}, affected={}", category, entities.size());
        return entities.size();
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByUserIdAndKeyword(
                currentUserProvider.requireUserId(), keyword.trim())
        );
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    /**
     * 在内存中对实体列表排序
     */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                .toList();
            default -> entities; // time 已经在数据库层面排序了
        };
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        // 统计必须按用户隔离，否则会泄露他人的知识库数量与提问量
        Long userId = currentUserProvider.requireUserId();
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.countByUserId(userId),
            ragChatMessageRepository.countByTypeAndUserId(MessageType.USER, userId),
            knowledgeBaseRepository.sumAccessCountByUserId(userId),
            knowledgeBaseRepository.countByUserIdAndVectorStatus(userId, VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByUserIdAndVectorStatus(userId, VectorStatus.PROCESSING)
        );
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件（带归属校验，禁止下载他人文件）
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = requireOwned(id);

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return requireOwned(id);
    }
}
