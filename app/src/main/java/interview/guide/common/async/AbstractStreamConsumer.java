package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    @PostConstruct
    public void init() {
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
                    AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload;
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e);
            ackMessage(messageId);
            return;
        }

        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            if (shouldSkip(payload)) {
                ackMessage(messageId);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            if (!tryMarkProcessing(payload)) {
                ackMessage(messageId);
                log.info("{} task was not claimed: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            processBusiness(payload);
            markCompleted(payload);
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}, error={}", taskDisplayName(), payloadIdentifier(payload),
                ErrorLogSanitizer.summarize(e), ErrorLogSanitizer.forLogging(e));
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + "；" + failureReason(e)
                ));
            }
            ackMessage(messageId);
        }
    }

    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * 把异常类型映射成固定、可执行的中文提示。
     *
     * <p>只读取异常类名，绝不回显 message / cause 文本：上游 SDK 可能把 API Key、
     * Prompt 或文档片段塞进异常消息。这样既保留脱敏约束，又让用户能看懂
     * 「为什么向量化失败」，而不是只看到一句 failed after retry 3。</p>
     */
    protected String failureReason(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getCause()) {
            String type = cursor.getClass().getSimpleName();
            if (type.contains("Unauthorized") || type.contains("Authentication")) {
                return "供应商认证失败（401）：API Key 无效或已过期，请在「我的 AI 设置」中更新";
            }
            if (type.contains("PermissionDenied") || type.contains("Forbidden")) {
                return "供应商拒绝访问（403）：请检查模型权限或账户额度";
            }
            if (type.contains("RateLimit") || type.contains("TooManyRequests")) {
                return "供应商限流：请稍后重试";
            }
            if (type.contains("BadRequest") || type.contains("InvalidRequest")) {
                return "供应商请求参数错误：请检查模型名与向量维度配置";
            }
            if (type.contains("NotFound")) {
                return "供应商模型或接口不存在：请检查 Base URL 与模型名";
            }
            if (type.contains("Connect") || type.contains("Timeout") || type.contains("UnknownHost")) {
                return "无法连接供应商：请检查 Base URL 与网络连通性";
            }
        }
        return "未知错误（" + ErrorLogSanitizer.summarize(error) + "）";
    }

    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected boolean shouldSkip(T payload) {
        return false;
    }

    protected abstract void markProcessing(T payload);

    /**
     * 尝试领取任务。默认保持原有消费者的状态更新语义。
     */
    protected boolean tryMarkProcessing(T payload) {
        markProcessing(payload);
        return true;
    }

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);
}
