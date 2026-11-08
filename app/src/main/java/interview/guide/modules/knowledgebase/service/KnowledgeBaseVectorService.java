package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.AiExecutionContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private static final String METADATA_CHUNK_STRATEGY = "chunk_strategy";
    private static final String CONFIG_KEY_EMBEDDING_SIGNATURE = "embeddingSignature";

    /** 用户级向量库注册表：每次向量化/检索都按当前用户解析 Embedding 凭证。 */
    private final UserVectorStoreProvider userVectorStoreRegistry;
    private final TextSplitter textSplitter;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final KnowledgeBaseVectorProperties vectorProperties;
    private final KnowledgeBasePersistenceService persistenceService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public KnowledgeBaseVectorService(
        UserVectorStoreProvider userVectorStoreRegistry,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor,
        KnowledgeBaseVectorProperties vectorProperties,
        KnowledgeBasePersistenceService persistenceService,
        KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        this.userVectorStoreRegistry = userVectorStoreRegistry;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        this.vectorProperties = vectorProperties;
        this.persistenceService = persistenceService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        // Chunk 策略由 app.ai.rag.vectorization 配置驱动（仅 Spring AI 原生参数，无 overlap）
        this.textSplitter = vectorProperties.createTextSplitter();
    }

    /**
     * 测试专用构造器：直接绑定一个 VectorStore，不解析用户凭证。
     * 生产代码不会走这里（Spring 只会使用带 {@code @Autowired} 的构造器）。
     */
    KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this(new FixedVectorStoreRegistry(vectorStore), vectorRepository, null,
            new KnowledgeBaseVectorProperties(), null, null);
    }

    /**
     * 测试专用构造器（保留原有可注入 TransactionalExecutor / Properties 的形态）。
     */
    KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository,
                               TransactionalExecutor transactionalExecutor,
                               KnowledgeBaseVectorProperties vectorProperties,
                               KnowledgeBasePersistenceService persistenceService) {
        this(new FixedVectorStoreRegistry(vectorStore), vectorRepository, transactionalExecutor,
            vectorProperties, persistenceService, null);
    }

    /**
     * 解析本次调用使用的向量库：必须来自用户级注册表，绝不复用全局单例。
     */
    private VectorStore resolveVectorStore(AiExecutionContext context) {
        if (userVectorStoreRegistry == null) {
            throw new BusinessException(ErrorCode.EMBEDDING_CREDENTIAL_NOT_CONFIGURED);
        }
        return userVectorStoreRegistry.get(context);
    }

    private String embeddingSignature(AiExecutionContext context) {
        return userVectorStoreRegistry == null || context == null
            ? null : userVectorStoreRegistry.embeddingSignature(context);
    }

    /** 测试专用：固定向量库，不解析用户凭证。 */
    private static final class FixedVectorStoreRegistry implements UserVectorStoreProvider {

        private final VectorStore fixed;

        private FixedVectorStoreRegistry(VectorStore fixed) {
            this.fixed = fixed;
        }

        @Override
        public VectorStore get(AiExecutionContext context) {
            return fixed;
        }

        @Override
        public String embeddingSignature(AiExecutionContext context) {
            return null;
        }
    }

    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        vectorizeAndStore(knowledgeBaseId, content, () -> { });
    }

    /**
     * 带进度心跳回调的向量化：分块完成与每个 Embedding 批次后回调（由调用方决定节流）。
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content, Runnable progressHeartbeat) {
        vectorizeAndStore(knowledgeBaseId, content, null, progressHeartbeat);
    }

    /**
     * 带执行代次的向量化。临时向量仅在数据库锁内确认当前代次仍有效后才会被提升。
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content, String attemptId,
                                  Runnable progressHeartbeat) {
        vectorizeAndStore(knowledgeBaseId, content, attemptId, progressHeartbeat,
            new SourceInfo("", "", null));
    }

    public void vectorizeAndStore(AiExecutionContext context, Long knowledgeBaseId, String content,
                                  String attemptId, Runnable progressHeartbeat) {
        vectorizeAndStore(context, knowledgeBaseId, content, attemptId, progressHeartbeat,
            new SourceInfo("", "", null));
    }

    /** 文件哈希只用于可复现标识，不用于鉴权；来源信息由已加载的业务实体提供。 */
    public record SourceInfo(String name, String filename, String fileHash) {}

    public void vectorizeAndStore(Long knowledgeBaseId, String content, String attemptId,
                                  Runnable progressHeartbeat, SourceInfo source) {
        vectorizeAndStore(null, knowledgeBaseId, content, attemptId, progressHeartbeat, source);
    }

    /**
     * 使用当前用户自己的 Embedding 凭证完成向量化并写入 pgvector。
     *
     * @param context 用户级 Embedding 上下文；生产路径必须非空
     */
    public void vectorizeAndStore(AiExecutionContext context, Long knowledgeBaseId, String content,
                                  String attemptId, Runnable progressHeartbeat, SourceInfo source) {
        String jobId = null;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            VectorStore vectorStore = resolveVectorStore(context);
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: userId={}, kbId={}, jobId={}, contentLength={}",
                context == null ? null : context.userId(), knowledgeBaseId, jobId, content.length());

            // 1. 将文本分块
            String configJson = vectorConfigJson(embeddingSignature(context));
            String fileHash = source.fileHash() == null || source.fileHash().isBlank()
                ? DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8)) : source.fileHash();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source_kb_id", knowledgeBaseId.toString());
            metadata.put("source_name", source.name() == null ? "" : source.name());
            metadata.put("source_filename", source.filename() == null ? "" : source.filename());
            metadata.put("chunk_strategy", vectorProperties.getStrategyVersion());
            List<Document> chunks = textSplitter.apply(List.of(new Document(content, metadata)));
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("chunk_index", i);
                chunk.getMetadata().put("total_chunks", chunks.size());
                // 仅 metadata 稳定；保留随机 Document ID，避免 pending upsert 覆盖旧正式向量。
                chunk.getMetadata().put("source_id", DigestUtils.md5DigestAsHex(
                    (fileHash + "\n" + configJson + "\n" + i).getBytes(StandardCharsets.UTF_8)));
            }
            
            log.info("文本分块完成: {} 个chunks", chunks.size());
            
            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, jobId);

            // 分块完成心跳
            progressHeartbeat.run();

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
                // Embedding 批次心跳（调用方节流）
                progressHeartbeat.run();
            }
            if (attemptId != null && persistenceService != null) {
                persistenceService.activateVectorJobAndUpdateSnapshot(
                    knowledgeBaseId, attemptId, jobId, totalChunks, configJson);
            } else {
                activateVectorJob(knowledgeBaseId, jobId);
                // 兼容直接调用：提升成功后再以独立短事务写入快照
                updateVectorizationSnapshot(knowledgeBaseId, totalChunks, configJson);
            }
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            // 保留 cause（只保留类型，不回显 message）：异步消费者据此把 401 / 限流等
            // 真实原因写成可读提示，避免用户只看到「failed after retry 3」
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败", e);
        }
    }

    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
            // 在 add 前写入策略标识：promote 只改 kb_id，不会回填
            chunk.getMetadata().put(METADATA_CHUNK_STRATEGY, vectorProperties.getStrategyVersion());
        });
    }
    
    /**
     * 基于多个知识库进行相似度搜索
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        return similaritySearch(null, query, knowledgeBaseIds, topK, minScore);
    }

    /**
     * 使用当前用户自己的 Embedding 凭证检索向量。
     *
     * <p>检索前会校验「该知识库入库时使用的 Embedding 模型」与当前用户模型一致，
     * 不一致时明确报错，避免不同向量空间混算导致静默错误结果。</p>
     */
    public List<Document> similaritySearch(AiExecutionContext context, String query,
                                           List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: userId={}, queryLength={}, kbIds={}, topK={}, minScore={}",
            context == null ? null : context.userId(), query.length(), knowledgeBaseIds, topK, minScore);

        VectorStore vectorStore = resolveVectorStore(context);
        verifyEmbeddingConsistency(context, knowledgeBaseIds);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            // Apply topK limiting in case VectorStore returns more than requested
            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            return limitedResults;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}",
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            return similaritySearchFallback(vectorStore, query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(VectorStore vectorStore, String query,
                                                    List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", ErrorLogSanitizer.summarize(e),
                ErrorLogSanitizer.forLogging(e));
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败");
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     * 
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId,
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId));
    }

    private void activateVectorJob(Long knowledgeBaseId, String jobId) {
        runVectorRepositoryMutation(() -> {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        });
    }

    /**
     * 向量化成功后的配置快照：chunkCount、vectorConfig（无密钥 JSON）、vectorizedAt。
     * 失败（含清理）路径不会调用；与 Embedding 写入不在同一事务。
     */
    private void updateVectorizationSnapshot(Long knowledgeBaseId, int chunkCount,
                                             String configJson) {
        if (persistenceService == null) {
            return;
        }
        Runnable update = () -> persistenceService.updateVectorizationSnapshot(
            knowledgeBaseId, chunkCount, configJson);
        if (transactionalExecutor == null) {
            update.run();
            return;
        }
        transactionalExecutor.run(update);
    }

    private String vectorConfigJson(String embeddingSignature) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("splitter", vectorProperties.getSplitter());
        config.put("chunkSize", vectorProperties.getChunkSize());
        config.put("minChunkSizeChars", vectorProperties.getMinChunkSizeChars());
        config.put("minChunkLengthToEmbed", vectorProperties.getMinChunkLengthToEmbed());
        config.put("maxNumChunks", vectorProperties.getMaxNumChunks());
        config.put("keepSeparator", vectorProperties.isKeepSeparator());
        config.put("punctuationMarks", vectorProperties.getPunctuationMarks());
        config.put("strategyVersion", vectorProperties.getStrategyVersion());
        if (embeddingSignature != null) {
            // 记录入库时使用的 Embedding 模型，检索时校验"入库/查询同一模型"
            config.put(CONFIG_KEY_EMBEDDING_SIGNATURE, embeddingSignature);
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (tools.jackson.core.JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "向量化配置快照序列化失败");
        }
    }

    /**
     * 校验知识库入库时使用的 Embedding 模型与当前用户模型一致。
     *
     * <p>历史数据（阶段一）没有记录签名，跳过校验以保持向后兼容；
     * 阶段二起新写入的向量都会带签名。</p>
     */
    private void verifyEmbeddingConsistency(AiExecutionContext context, List<Long> knowledgeBaseIds) {
        String expected = embeddingSignature(context);
        if (expected == null || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
            || knowledgeBaseRepository == null) {
            return;
        }
        List<KnowledgeBaseEntity> entities = knowledgeBaseRepository.findAllById(knowledgeBaseIds);
        for (KnowledgeBaseEntity entity : entities) {
            String recorded = readEmbeddingSignature(entity.getVectorConfig());
            if (recorded != null && !recorded.equals(expected)) {
                log.warn("知识库 Embedding 模型与当前用户配置不一致: kbId={}, recorded={}, current={}",
                    entity.getId(), recorded, expected);
                throw new BusinessException(ErrorCode.EMBEDDING_MODEL_MISMATCH);
            }
        }
    }

    private String readEmbeddingSignature(String vectorConfig) {
        if (vectorConfig == null || vectorConfig.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                vectorConfig, new TypeReference<Map<String, Object>>() {});
            Object value = parsed.get(CONFIG_KEY_EMBEDDING_SIGNATURE);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            log.debug("解析知识库向量配置失败，跳过 Embedding 一致性校验: error={}",
                ErrorLogSanitizer.summarize(e));
            return null;
        }
    }

    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, ErrorLogSanitizer.summarize(cleanupError),
                ErrorLogSanitizer.forLogging(cleanupError));
        }
    }

    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }
}
