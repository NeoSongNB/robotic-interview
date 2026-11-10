package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeAnalysisEntity;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final FileHashService fileHashService;
    private final CurrentUserProvider currentUserProvider;
    
    /**
     * 检查简历是否已存在（基于文件内容hash）
     * 
     * @param file 上传的文件
     * @return 如果存在返回已有的简历实体，否则返回空
     */
    public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
        try {
            String fileHash = fileHashService.calculateHash(file);
            // 去重范围限定在当前用户内：不同用户上传同一份文件互不影响。
            Optional<ResumeEntity> existing = resumeRepository.findByUserIdAndFileHash(
                currentUserProvider.requireUserId(), fileHash);
            
            if (existing.isPresent()) {
                log.info("检测到重复简历: hash={}", fileHash);
                ResumeEntity resume = existing.get();
                resume.incrementAccessCount();
                resumeRepository.save(resume);
            }
            
            return existing;
        } catch (Exception e) {
            log.error("检查简历重复时出错: {}", e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * 保存新简历
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeEntity saveResume(MultipartFile file, String resumeText,
                                   String storageKey, String storageUrl, String textReference) {
        try {
            String fileHash = fileHashService.calculateHash(file);
            
            ResumeEntity resume = new ResumeEntity();
            // 归属用户只能来自服务端认证上下文。
            resume.setUserId(currentUserProvider.requireUserId());
            resume.setFileHash(fileHash);
            resume.setOriginalFilename(file.getOriginalFilename());
            resume.setFileSize(file.getSize());
            resume.setContentType(file.getContentType());
            resume.setStorageKey(storageKey);
            resume.setStorageUrl(storageUrl);
            resume.setResumeText(resumeText);
            resume.setTextModelReference(textReference);
            // 进展时间初始化：NULL 会被 stale 扫描的阈值比较排除
            resume.setAnalyzeUpdatedAt(java.time.LocalDateTime.now());

            ResumeEntity saved = resumeRepository.save(resume);
            log.info("简历已保存: id={}, hash={}", saved.getId(), fileHash);
            
            return saved;
        } catch (Exception e) {
            log.error("保存简历失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存简历失败");
        }
    }
    
    /**
     * 回填简历正文（独立短事务，供 Stream 消费者从 RustFS 恢复文本后使用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateResumeText(Long resumeId, String resumeText) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        resume.setResumeText(resumeText);
        resumeRepository.save(resume);
    }

    /**
     * 保存简历评测结果
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeAnalysisEntity saveAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        return saveAnalysisInternal(resume, analysis);
    }

    private ResumeAnalysisEntity saveAnalysisInternal(ResumeEntity resume,
                                                       ResumeAnalysisResponse analysis) {
        try {
            // 使用 MapStruct 映射基础字段
            ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(analysis);
            entity.setResume(resume);

            // JSON 字段需要手动序列化
            entity.setStrengthsJson(objectMapper.writeValueAsString(analysis.strengths()));
            entity.setSuggestionsJson(objectMapper.writeValueAsString(analysis.suggestions()));

            ResumeAnalysisEntity saved = analysisRepository.save(entity);
            log.info("简历评测结果已保存: analysisId={}, resumeId={}, score={}",
                    saved.getId(), resume.getId(), analysis.overallScore());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化评测结果失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "保存评测结果失败");
        }
    }

    /**
     * 锁定简历行并校验当前执行代次后保存分析，防止超时回收后的旧消费者落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeAnalysisEntity saveAnalysisIfOwned(Long resumeId, String attemptId,
                                                     ResumeAnalysisResponse analysis) {
        ResumeEntity resume = resumeRepository.findByIdForUpdate(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        if (resume.getAnalyzeStatus() != interview.guide.common.model.AsyncTaskStatus.PROCESSING
            || !Objects.equals(resume.getAnalyzeAttemptId(), attemptId)) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析任务执行权已失效");
        }
        resume.setAnalyzeUpdatedAt(java.time.LocalDateTime.now());
        resumeRepository.save(resume);
        return saveAnalysisInternal(resume, analysis);
    }
    
    /**
     * 获取简历的最新评测结果
     */
    public Optional<ResumeAnalysisEntity> getLatestAnalysis(Long resumeId) {
        return Optional.ofNullable(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId));
    }
    
    /**
     * 获取简历的最新评测结果（返回DTO）
     */
    public Optional<ResumeAnalysisResponse> getLatestAnalysisAsDTO(Long resumeId) {
        return getLatestAnalysis(resumeId).map(this::entityToDTO);
    }
    
    /**
     * 获取当前登录用户的所有简历。
     */
    public List<ResumeEntity> findAllResumes() {
        return resumeRepository.findAllByUserIdOrderByUploadedAtDesc(
            currentUserProvider.requireUserId());
    }
    
    /**
     * 获取简历的所有评测记录
     */
    public List<ResumeAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
    }
    
    /**
     * 将实体转换为DTO
     */
    public ResumeAnalysisResponse entityToDTO(ResumeAnalysisEntity entity) {
        try {
            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson() != null ? entity.getStrengthsJson() : "[]",
                    new TypeReference<>() {
                    }
            );
            
            List<ResumeAnalysisResponse.Suggestion> suggestions = objectMapper.readValue(
                entity.getSuggestionsJson() != null ? entity.getSuggestionsJson() : "[]",
                    new TypeReference<>() {
                    }
            );
            
            return new ResumeAnalysisResponse(
                entity.getOverallScore(),
                resumeMapper.toScoreDetail(entity),  // 使用MapStruct自动映射
                entity.getSummary(),
                strengths,
                suggestions,
                entity.getResume().getResumeText()
            );
        } catch (JacksonException e) {
            log.error("反序列化评测结果失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "获取评测结果失败");
        }
    }
    
    /**
     * 根据 ID 获取简历（<b>内部使用</b>：异步消费者无认证上下文，不做归属校验）。
     *
     * <p>对外 HTTP 接口必须使用 {@link #findOwnedById(Long)}。</p>
     */
    public Optional<ResumeEntity> findById(Long id) {
        return resumeRepository.findById(id);
    }

    /**
     * 带归属校验的查询：非本人简历一律按"不存在"处理，避免资源枚举。
     */
    public Optional<ResumeEntity> findOwnedById(Long id) {
        Long userId = currentUserProvider.requireUserId();
        return resumeRepository.findByIdAndUserId(id, userId);
    }

    /**
     * 获取简历并强制校验归属，供对外接口使用。
     */
    public ResumeEntity requireOwnedResume(Long id) {
        return findOwnedById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    }
    
    /**
     * 删除简历及其所有关联数据
     * 包括：简历分析记录、面试会话（会自动删除面试答案）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long id) {
        // 对象级权限校验：非本人简历按"不存在"返回，避免泄露资源存在性。
        ResumeEntity resume = requireOwnedResume(id);
        
        // 1. 删除所有简历分析记录
        List<ResumeAnalysisEntity> analyses = analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id);
        if (!analyses.isEmpty()) {
            analysisRepository.deleteAll(analyses);
            log.info("已删除 {} 条简历分析记录", analyses.size());
        }
        
        // 2. 删除简历实体（面试会话会在服务层删除）
        resumeRepository.delete(resume);
        log.info("简历已删除: id={}, filename={}", id, resume.getOriginalFilename());
    }
}
