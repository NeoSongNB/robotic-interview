package interview.guide.common.model;

/**
 * 异步任务状态枚举
 * 用于简历分析等异步任务
 */
public enum AsyncTaskStatus {
    PENDING,     // 待处理
    PROCESSING,  // 处理中
    COMPLETED,   // 完成
    FAILED       // 失败
}
