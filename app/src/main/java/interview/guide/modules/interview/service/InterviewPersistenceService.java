package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 面试持久化服务
 * 面试会话和答案的持久化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {
    
    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * 保存新的面试会话（支持可选简历）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", null);
    }

    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        List<InterviewQuestionDTO> questions,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
            skillId, difficulty, "NORMAL", requestId);
    }

    private InterviewSessionEntity saveSessionInternal(String sessionId, Long resumeId,
                                                       int totalQuestions,
                                                       List<InterviewQuestionDTO> questions,
                                                       String llmProvider,
                                                       String skillId,
                                                       String difficulty,
                                                       String sourceType,
                                                       String requestId) {
        try {
            InterviewSessionEntity session = new InterviewSessionEntity();
            // 归属用户只能来自认证上下文
            session.setUserId(currentUserProvider.requireUserId());
            session.setSessionId(sessionId);
            session.setRequestId(requestId);
            session.setTotalQuestions(totalQuestions);
            session.setCurrentQuestionIndex(0);
            session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
            session.setQuestionsJson(objectMapper.writeValueAsString(questions));
            session.setLlmProvider(llmProvider != null ? llmProvider : "default");
            session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
            session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);
            session.setSourceType(sourceType != null ? sourceType : "NORMAL");

            // 简历可选：有 resumeId 则关联简历（必须属于当前用户）
            if (resumeId != null) {
                Optional<ResumeEntity> resumeOpt = resumeRepository.findByIdAndUserId(
                    resumeId, currentUserProvider.requireUserId());
                resumeOpt.ifPresent(session::setResume);
            }

            InterviewSessionEntity saved = sessionRepository.save(session);
            log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}, sourceType={}",
                sessionId, skillId, resumeId, session.getSourceType());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化问题列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败");
        }
    }
    
    /**
     * 写操作的对象级校验入口。
     *
     * <p>存在登录上下文时强制按 userId 过滤，跨用户会话等同于"不存在"；
     * 异步评估消费者没有 SecurityContext，此时退化为按会话 ID 查询
     * （其会话 ID 由已校验归属的上游流程传入）。</p>
     */
    private Optional<InterviewSessionEntity> findSessionForWrite(String sessionId) {
        Long userId = currentUserProvider.userIdOrNull();
        if (userId == null) {
            return sessionRepository.findBySessionId(sessionId);
        }
        return sessionRepository.findBySessionIdAndUserId(sessionId, userId);
    }

    /**
     * 更新会话状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
        Optional<InterviewSessionEntity> sessionOpt = findSessionForWrite(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setStatus(status);
            if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
                status == InterviewSessionEntity.SessionStatus.EVALUATED) {
                session.setCompletedAt(LocalDateTime.now());
            }
            sessionRepository.save(session);
        }
    }

    /**
     * 更新评估状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        Optional<InterviewSessionEntity> sessionOpt = findSessionForWrite(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            } else {
                session.setEvaluateError(null);
            }
            sessionRepository.save(session);
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
        }
    }
    
    /**
     * 更新当前问题索引
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        Optional<InterviewSessionEntity> sessionOpt = findSessionForWrite(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setCurrentQuestionIndex(index);
            session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            sessionRepository.save(session);
        }
    }
    
    /**
     * 保存面试答案
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        Optional<InterviewSessionEntity> sessionOpt = findSessionForWrite(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewAnswerEntity answer = answerRepository
            .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
            .orElseGet(() -> {
                InterviewAnswerEntity created = new InterviewAnswerEntity();
                created.setSession(sessionOpt.get());
                created.setQuestionIndex(questionIndex);
                return created;
            });

        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        InterviewAnswerEntity saved = answerRepository.save(answer);
        log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}", 
                sessionId, questionIndex, score);
        
        return saved;
    }
    
    /**
     * 保存面试报告
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        try {
            Optional<InterviewSessionEntity> sessionOpt = findSessionForWrite(sessionId);
            if (sessionOpt.isEmpty()) {
                log.warn("会话不存在: {}", sessionId);
                return;
            }

            InterviewSessionEntity session = sessionOpt.get();
            session.setOverallScore(report.overallScore());
            session.setOverallFeedback(report.overallFeedback());
            session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
            session.setCompletedAt(LocalDateTime.now());

            sessionRepository.save(session);

            // 查询已存在的答案，建立索引
            List<InterviewAnswerEntity> existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
            java.util.Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewAnswerEntity::getQuestionIndex,
                    a -> a,
                    (a1, a2) -> a1
                ));

            // 建立参考答案索引
            java.util.Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
                .collect(java.util.stream.Collectors.toMap(
                    InterviewReportDTO.ReferenceAnswer::questionIndex,
                    r -> r,
                    (r1, r2) -> r1
                ));

            List<InterviewAnswerEntity> answersToSave = new java.util.ArrayList<>();

            // 遍历所有评估结果，更新或创建答案记录
            for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
                InterviewAnswerEntity answer = answerMap.get(eval.questionIndex());

                if (answer == null) {
                    // 未回答的题目，创建新记录
                    answer = new InterviewAnswerEntity();
                    answer.setSession(session);
                    answer.setQuestionIndex(eval.questionIndex());
                    answer.setQuestion(eval.question());
                    answer.setCategory(eval.category());
                    answer.setUserAnswer(null);  // 未回答
                    log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex());
                }

                // 更新评分和反馈
                answer.setScore(eval.score());
                answer.setFeedback(eval.feedback());

                // 设置参考答案和关键点
                InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
                if (refAns != null) {
                    answer.setReferenceAnswer(refAns.referenceAnswer());
                    if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
                        answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
                    }
                }

                answersToSave.add(answer);
            }

            answerRepository.saveAll(answersToSave);
            log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
                sessionId, report.overallScore(), answersToSave.size());

        } catch (JacksonException e) {
            log.error("序列化报告失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 根据会话ID获取会话（<b>内部使用</b>：异步消费者无认证上下文，不做归属校验）。
     *
     * <p>对外 HTTP 接口必须使用 {@link #requireOwnedSession(String)}。</p>
     */
    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    // ========== BYOK 阶段一：对象级权限校验 ==========

    /**
     * 带归属校验的会话查询。
     *
     * <p>非本人会话返回 empty（上层统一按"不存在"处理，避免资源枚举）。</p>
     */
    public Optional<InterviewSessionEntity> findOwnedSession(String sessionId) {
        return sessionRepository.findBySessionIdAndUserId(
            sessionId, currentUserProvider.requireUserId());
    }

    /**
     * 取出会话并强制校验归属；跨用户访问抛 {@code INTERVIEW_SESSION_NOT_FOUND}（404）。
     */
    public InterviewSessionEntity requireOwnedSession(String sessionId) {
        return findOwnedSession(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    }

    /**
     * 幂等键按用户隔离：不同用户即使使用相同 requestId，也不会命中彼此的会话。
     *
     * <p>已删除原本不区分用户的 {@code findByRequestId(String)}：幂等键可能被不同用户
     * 重复使用，全局查询会命中他人会话。</p>
     */
    public Optional<InterviewSessionEntity> findByUserIdAndRequestId(String requestId) {
        return sessionRepository.findByUserIdAndRequestId(
            currentUserProvider.requireUserId(), requestId);
    }
    
    /**
     * 获取当前用户在指定简历下的面试记录。
     */
    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByUserIdAndResumeIdOrderByCreatedAtDesc(
            currentUserProvider.requireUserId(), resumeId);
    }

    /**
     * 获取当前用户的全部面试记录（按创建时间倒序）。
     */
    public List<InterviewSessionEntity> findAll() {
        return sessionRepository.findAllByUserIdOrderByCreatedAtDesc(
            currentUserProvider.requireUserId());
    }
    
    /**
     * 删除简历的所有面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId) {
        // 只清理当前用户自己的会话，避免跨用户误删
        List<InterviewSessionEntity> sessions = sessionRepository.findByUserIdAndResumeIdOrderByCreatedAtDesc(
            currentUserProvider.requireUserId(), resumeId);
        if (!sessions.isEmpty()) {
            sessionRepository.deleteAll(sessions);
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
        }
    }
    
    /**
     * 删除单个面试会话
     * 由于InterviewSessionEntity设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        // 对象级权限校验：非本人会话按"不存在"处理
        InterviewSessionEntity session = requireOwnedSession(sessionId);
        sessionRepository.delete(session);
        log.info("已删除面试会话: sessionId={}", sessionId);
    }
    
    /**
     * 查找未完成的面试会话（CREATED或IN_PROGRESS状态）
     */
    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
            InterviewSessionEntity.SessionStatus.CREATED,
            InterviewSessionEntity.SessionStatus.IN_PROGRESS
        );
        // 只能续接当前用户自己的未完成面试
        return sessionRepository.findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
            currentUserProvider.requireUserId(), resumeId, unfinishedStatuses);
    }
    
    /**
     * 根据会话ID查找所有答案
     */
    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    private static final int MAX_HISTORICAL_QUESTIONS = 60;

    /**
     * 获取历史提问列表（结构化，按分类压缩用）。
     * 有 resumeId 时精确匹配 resumeId + skillId；无 resumeId 时按 skillId 查全部（通用模式兜底）。
     */
    public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
        Long userId = currentUserProvider.requireUserId();
        List<InterviewSessionEntity> sessions;
        if (resumeId != null) {
            sessions = sessionRepository.findTop10ByUserIdAndResumeIdAndSkillIdOrderByCreatedAtDesc(
                userId, resumeId, skillId);
        } else {
            // 历史题去重必须限定在当前用户内，否则会把他人的题目当作"已出过"
            sessions = sessionRepository.findTop10ByUserIdAndSkillIdOrderByCreatedAtDesc(userId, skillId);
        }

        log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<HistoricalQuestion> result = sessions.stream()
            .map(InterviewSessionEntity::getQuestionsJson)
            .filter(json -> json != null && !json.isEmpty())
            .flatMap(json -> {
                try {
                    List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
                        new TypeReference<List<InterviewQuestionDTO>>() {});
                    return questions.stream()
                        .filter(q -> !q.isFollowUp())
                        .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
                } catch (Exception e) {
                    log.error("解析历史问题JSON失败", e);
                    return java.util.stream.Stream.<HistoricalQuestion>empty();
                }
            })
            .filter(hq -> seen.add(hq.question()))
            .limit(MAX_HISTORICAL_QUESTIONS)
            .toList();

        log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
            result.stream().collect(java.util.stream.Collectors.groupingBy(
                hq -> hq.type() != null ? hq.type() : "GENERAL",
                java.util.stream.Collectors.counting())));

        return result;
    }
}
