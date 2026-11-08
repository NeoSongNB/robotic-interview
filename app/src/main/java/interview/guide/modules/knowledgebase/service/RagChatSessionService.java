package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.QuerySource;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;

/**
 * RAG 聊天会话服务
 * 提供RAG聊天会话的创建、获取、更新、删除等操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQueryProperties queryProperties;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 创建新会话
     */
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        // 验证知识库存在且属于当前用户
        List<KnowledgeBaseEntity> knowledgeBases =
            requireOwnedKnowledgeBases(request.knowledgeBaseIds());

        // 创建会话
        RagChatSessionEntity session = new RagChatSessionEntity();
        // 归属用户只能来自认证上下文
        session.setUserId(currentUserProvider.requireUserId());
        session.setTitle(request.title() != null && !request.title().isBlank()
            ? request.title()
            : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("创建 RAG 聊天会话: id={}, title={}", session.getId(), session.getTitle());

        return ragChatMapper.toSessionDTO(session);
    }

    /**
     * 获取会话列表
     */
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllByUserIdOrderByPinnedAndUpdatedAtDesc(
                currentUserProvider.requireUserId())
            .stream()
            .map(ragChatMapper::toSessionListItemDTO)
            .toList();
    }

    /**
     * 取出 RAG 会话并强制校验归属；跨用户访问抛 404。
     */
    public RagChatSessionEntity requireOwnedSession(Long sessionId) {
        return sessionRepository
            .findByIdAndUserId(sessionId, currentUserProvider.requireUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
    }

    private RagChatSessionEntity requireOwnedSessionWithKnowledgeBases(Long sessionId) {
        return sessionRepository
            .findByIdAndUserIdWithKnowledgeBases(sessionId, currentUserProvider.requireUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
    }

    /**
     * 校验一组知识库都属于当前用户。
     */
    private List<KnowledgeBaseEntity> requireOwnedKnowledgeBases(List<Long> knowledgeBaseIds) {
        Long userId = currentUserProvider.requireUserId();
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository.findAllById(knowledgeBaseIds);
        boolean allOwned = knowledgeBases.size() == knowledgeBaseIds.size()
            && knowledgeBases.stream().allMatch(kb -> userId.equals(kb.getUserId()));
        if (!allOwned) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }
        return knowledgeBases;
    }

    /**
     * 获取会话详情（包含消息）
     * 分两次查询避免笛卡尔积问题
     */
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        // 先加载会话和知识库（带归属校验）
        RagChatSessionEntity session = requireOwnedSessionWithKnowledgeBases(sessionId);

        // 再单独加载消息（避免笛卡尔积）
        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);

        // 转换知识库列表
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
            new java.util.ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    /**
     * 准备流式消息（保存用户消息，创建 AI 消息占位）
     *
     * @return AI 消息的 ID
     */
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = requireOwnedSessionWithKnowledgeBases(sessionId);

        // 获取当前消息数量作为起始顺序
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建 AI 消息占位（未完成）
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息数量
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());

        return assistantMessage.getId();
    }

    /**
     * 流式响应完成后更新消息。
     *
     * <p>运行在 Reactor 回调线程，此时通常没有 SecurityContext，因此不能直接取当前用户；
     * 改为要求传入 {@code sessionId}（该会话在 SSE 订阅前已被
     * {@link #requireOwnedSessionWithKnowledgeBases(Long)} 校验归属），
     * 并把消息限定在该会话内，避免跨会话/跨用户写入。</p>
     */
    @Transactional
    public void completeStreamMessage(Long sessionId, Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findByIdAndSessionId(messageId, sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        log.info("完成流式消息: messageId={}, contentLength={}", messageId, content.length());
    }

    /**
     * 获取流式回答（带多轮上下文）
     */
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        return getStreamAnswer(sessionId, question, null);
    }

    /** 带执行轨迹的流式问答；轨迹用于在流末尾回传本次引用来源。 */
    public Flux<String> getStreamAnswer(Long sessionId, String question,
                                        java.util.function.Consumer<RagQueryExecution> trace) {
        // SSE 订阅前校验会话归属
        RagChatSessionEntity session = requireOwnedSessionWithKnowledgeBases(sessionId);

        List<Long> kbIds = session.getKnowledgeBaseIds();
        List<Message> history = queryProperties.getHistory().isEnabled()
            ? loadHistoryMessages(sessionId) : List.of();

        log.info("加载历史上下文: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStream(kbIds, question, history, trace);
    }

    /** 会话关联的知识库 id（已做归属校验），供 Controller 构造引用来源。 */
    public List<Long> getKnowledgeBaseIds(Long sessionId) {
        return requireOwnedSessionWithKnowledgeBases(sessionId).getKnowledgeBaseIds();
    }

    /**
     * 把本次流式问答的执行轨迹转成引用来源。
     * 没有检索片段时返回空列表，调用方据此不发额外 SSE 事件，保持原有事件序列。
     */
    public List<QuerySource> buildSources(Long sessionId, RagQueryExecution execution) {
        if (execution == null || execution.retrievedDocs() == null || execution.retrievedDocs().isEmpty()) {
            return List.of();
        }
        return queryService.buildSources(execution, getKnowledgeBaseIds(sessionId));
    }

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = requireOwnedSession(sessionId);

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = requireOwnedSession(sessionId);

        // 处理 null 值（兼容旧数据）
        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    /**
     * 更新会话的知识库关联
     */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = requireOwnedSession(sessionId);

        // 关联的知识库也必须是当前用户的
        List<KnowledgeBaseEntity> knowledgeBases = requireOwnedKnowledgeBases(knowledgeBaseIds);

        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);

        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        // 归属校验：非本人会话按"不存在"处理
        RagChatSessionEntity session = requireOwnedSession(sessionId);
        sessionRepository.delete(session);

        log.info("删除会话: sessionId={}", sessionId);
    }

    // ========== 私有方法 ==========

    /**
     * 加载会话中最近的历史消息作为多轮上下文。
     * 排除当前轮的 user 消息（prepareStreamMessage 中 completed=true 但尚未回答）。
     */
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = messageRepository
            .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) {
            return List.of();
        }

        // 查询结果按 messageOrder DESC 排列，最后一条（DESC 首条）是当前轮的 user 消息，排除
        List<RagChatMessageEntity> historyMessages = recent.size() <= 1
            ? List.of()
            : recent.subList(1, recent.size());

        // 反转为正序（时间从早到晚）
        return historyMessages.reversed().stream()
            .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                ? (Message) new UserMessage(m.getContent())
                : (Message) new AssistantMessage(m.getContent()))
            .toList();
    }

    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }
}
