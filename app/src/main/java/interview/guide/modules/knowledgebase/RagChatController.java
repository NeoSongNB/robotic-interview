package interview.guide.modules.knowledgebase;

import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateKnowledgeBasesRequest;
import interview.guide.modules.knowledgebase.model.QuerySource;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateTitleRequest;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import interview.guide.modules.knowledgebase.service.RagQueryExecution;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 聊天控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {

    private final RagChatSessionService sessionService;

    /** 流式回答末尾的引用来源哨兵；前端识别后从答案文本里剥离，只作结构化展示。 */
    public static final String SOURCES_PREFIX = "@@SOURCES@@";

    /**
     * 创建新会话
     */
    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success(null);
    }

    /**
     * 删除会话
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * 发送消息（流式SSE）
     * 流式响应设计：
     * 1. 先同步保存用户消息和创建 AI 消息占位
     * 2. 返回流式响应
     * 3. 流式完成后通过回调更新消息
     */
    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("收到 RAG 聊天流式请求: sessionId={}, questionLength={}, 线程: {} (虚拟线程: {})",
            sessionId, request.question().length(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 2. 获取流式响应；执行轨迹用于在流末尾回传本次引用来源
        StringBuilder fullContent = new StringBuilder();
        AtomicReference<RagQueryExecution> execution = new AtomicReference<>();

        return sessionService.getStreamAnswer(sessionId, request.question(), execution::set)
            .doOnNext(fullContent::append)
            // 使用 ServerSentEvent 包装，转义换行符避免破坏 SSE 格式
            .map(chunk -> ServerSentEvent.<String>builder()
                .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                .build())
            .concatWith(Flux.defer(() -> {
                List<QuerySource> sources;
                try {
                    sources = sessionService.buildSources(sessionId, execution.get());
                } catch (Exception e) {
                    log.warn("构造引用来源失败: sessionId={}, error={}", sessionId, e.getMessage());
                    return Flux.empty();
                }
                // 未检索到片段时不追加事件，保持原有纯文本 SSE 事件序列不变
                if (sources.isEmpty()) {
                    return Flux.empty();
                }
                return Flux.just(ServerSentEvent.<String>builder()
                    .data(SOURCES_PREFIX + toSourcesJson(sources))
                    .build());
            }))
            .doOnComplete(() -> {
                // 3. 流式完成后更新消息内容
                sessionService.completeStreamMessage(sessionId, messageId, fullContent.toString());
                log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId);
            })
            .doOnCancel(() -> {
                // 客户端中断时保存已生成的部分内容，并结束 AI 消息占位
                String content = !fullContent.isEmpty()
                    ? fullContent.toString()
                    : "【已中断】回答生成已取消";
                sessionService.completeStreamMessage(sessionId, messageId, content);
                log.info("RAG 聊天流式取消: sessionId={}, messageId={}", sessionId, messageId);
            })
            .doOnError(e -> {
                // 错误时也保存已接收的内容
                String content = !fullContent.isEmpty()
                    ? fullContent.toString()
                    : "【错误】回答生成失败：" + e.getMessage();
                sessionService.completeStreamMessage(sessionId, messageId, content);
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e);
            });
    }

    /**
     * 手工序列化引用来源，避免为 Controller 引入额外构造依赖。
     * 所有字符串字段已在服务端压平换行并在此转义，保证是单行合法 JSON。
     */
    private static String toSourcesJson(List<QuerySource> sources) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) builder.append(',');
            QuerySource source = sources.get(i);
            builder.append("{\"index\":").append(source.index())
                .append(",\"knowledgeBaseId\":").append(nullableNumber(source.knowledgeBaseId()))
                .append(",\"knowledgeBaseName\":").append(jsonText(source.knowledgeBaseName()))
                .append(",\"filename\":").append(jsonText(source.filename()))
                .append(",\"chunkIndex\":").append(nullableNumber(source.chunkIndex()))
                .append(",\"score\":").append(nullableNumber(source.score()))
                .append(",\"excerpt\":").append(jsonText(source.excerpt()))
                .append(",\"storageUrl\":").append(jsonText(source.storageUrl()))
                .append('}');
        }
        return builder.append(']').toString();
    }

    private static String nullableNumber(Number value) {
        return value == null ? "null" : value.toString();
    }

    private static String jsonText(String value) {
        if (value == null) return "null";
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", " ").replace("\n", " ").replace("\t", " ");
        return "\"" + escaped + "\"";
    }
}
