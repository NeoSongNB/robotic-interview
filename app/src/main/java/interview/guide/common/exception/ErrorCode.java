package interview.guide.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    
    // ========== 通用错误 1xxx ==========
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    
    // ========== 简历模块错误 2xxx ==========
    RESUME_NOT_FOUND(2001, "简历不存在"),
    RESUME_PARSE_FAILED(2002, "简历解析失败"),
    RESUME_UPLOAD_FAILED(2003, "简历上传失败"),
    RESUME_DUPLICATE(2004, "简历已存在"),
    RESUME_FILE_TYPE_NOT_SUPPORTED(2006, "不支持的文件类型"),
    RESUME_ANALYSIS_FAILED(2007, "简历分析失败"),
    RESUME_ANALYSIS_NOT_FOUND(2008, "简历分析结果不存在"),
    RESUME_DOCUMENT_NOT_FOUND(2009, "在线简历不存在"),
    RESUME_DOCUMENT_VERSION_CONFLICT(2010, "简历已被更新，请刷新后重试"),
    RESUME_DOCUMENT_CONTENT_INVALID(2011, "简历数据格式不正确"),
    RESUME_EDIT_PROPOSAL_INVALID(2012, "AI 修改提案无效，请重新描述你的需求"),
    RESUME_EDIT_AI_FAILED(2013, "AI 生成修改提案失败"),
    
    // ========== 面试模块错误 3xxx ==========
    INTERVIEW_SESSION_NOT_FOUND(3001, "面试会话不存在"),
    INTERVIEW_SESSION_EXPIRED(3002, "面试会话已过期"),
    INTERVIEW_QUESTION_NOT_FOUND(3003, "面试问题不存在"),
    INTERVIEW_ALREADY_COMPLETED(3004, "面试已完成"),
    INTERVIEW_EVALUATION_FAILED(3005, "面试评估失败"),
    INTERVIEW_QUESTION_GENERATION_FAILED(3006, "面试问题生成失败"),
    INTERVIEW_NOT_COMPLETED(3007, "面试尚未完成"),
    INTERVIEW_ANSWER_SAVE_FAILED(3008, "面试答案保存失败"),
    INTERVIEW_QUESTION_INSUFFICIENT(3009, "符合条件的面试问题不足"),
    
    // ========== 存储模块错误 4xxx ==========
    STORAGE_UPLOAD_FAILED(4001, "文件上传失败"),
    STORAGE_DOWNLOAD_FAILED(4002, "文件下载失败"),
    STORAGE_DELETE_FAILED(4003, "文件删除失败"),
    
    // ========== 导出模块错误 5xxx ==========
    EXPORT_PDF_FAILED(5001, "PDF导出失败"),
    
    // ========== AI服务错误 7xxx ==========
    AI_SERVICE_UNAVAILABLE(7001, "AI服务暂时不可用，请稍后重试"),
    AI_SERVICE_TIMEOUT(7002, "AI服务响应超时"),
    AI_SERVICE_ERROR(7003, "AI服务调用失败"),
    AI_API_KEY_INVALID(7004, "AI服务密钥无效"),
    AI_RATE_LIMIT_EXCEEDED(7005, "AI服务调用频率超限"),

    // ========== 限流模块错误 8xxx ==========
    RATE_LIMIT_EXCEEDED(8001, "请求过于频繁，请稍后再试"),

    // ========== 语音面试模块错误 10xxx ==========
    VOICE_SESSION_NOT_FOUND(10001, "语音面试会话不存在"),
    VOICE_EVALUATION_FAILED(10004, "语音面试评估失败"),
    VOICE_EVALUATION_NOT_FOUND(10006, "语音面试评估结果不存在"),

    // ========== Provider管理模块错误 11xxx ==========
    PROVIDER_NOT_FOUND(11001, "LLM Provider 不存在"),
    PROVIDER_ALREADY_EXISTS(11002, "LLM Provider 已存在"),
    PROVIDER_CONFIG_READ_FAILED(11004, "读取 Provider 配置失败"),
    PROVIDER_CONFIG_WRITE_FAILED(11005, "写入 Provider 配置失败"),
    PROVIDER_TEST_FAILED(11006, "Provider 连通性测试失败"),
    PROVIDER_DEFAULT_CANNOT_DELETE(11007, "默认 Provider 不可删除"),
    MODULE_NOT_FOUND(11008, "模块不存在"),
    VOICE_CONFIG_READ_FAILED(11009, "读取语音服务配置失败"),
    VOICE_CONFIG_WRITE_FAILED(11010, "写入语音服务配置失败"),
    VOICE_CONFIG_TEST_FAILED(11011, "语音服务连通性测试失败"),

    // ========== 认证与用户模块错误 12xxx ==========
    AUTH_INVALID_CREDENTIALS(12001, "用户名或密码错误"),
    AUTH_TOKEN_INVALID(12002, "登录状态无效，请重新登录"),
    AUTH_TOKEN_EXPIRED(12003, "登录已过期，请重新登录"),
    AUTH_FORBIDDEN(12004, "无权访问该资源"),
    USER_NOT_FOUND(12005, "用户不存在"),
    USER_ALREADY_EXISTS(12006, "用户名已被占用"),
    USER_EMAIL_ALREADY_EXISTS(12007, "邮箱已被占用"),
    USER_DISABLED(12008, "账号已被禁用"),
    AUTH_REGISTER_DISABLED(12009, "当前环境已关闭注册功能"),

    // ========== 用户 AI 凭证模块错误 13xxx ==========
    USER_CREDENTIAL_NOT_CONFIGURED(13001, "尚未配置 AI 模型 API Key，请前往设置页配置"),
    USER_CREDENTIAL_INVALID(13002, "AI 模型 API Key 无效或已过期，请重新配置"),
    USER_CREDENTIAL_PROVIDER_NOT_ALLOWED(13003, "不支持的模型供应商"),
    USER_CREDENTIAL_BASEURL_NOT_ALLOWED(13004, "自定义服务地址不被允许"),
    USER_CREDENTIAL_ENCRYPTION_FAILED(13005, "凭据加密失败"),
    USER_CREDENTIAL_DECRYPTION_FAILED(13006, "凭据解密失败，请重新配置"),
    USER_CREDENTIAL_MODEL_NOT_ALLOWED(13008, "该供应商不支持所选模型"),
    PROVIDER_AUTHENTICATION_FAILED(13009, "模型供应商认证失败，请检查 API Key 是否正确或已过期"),
    USER_CREDENTIAL_TEST_FAILED(13010, "模型连接测试失败"),
    AI_CONTEXT_USER_UNAVAILABLE(13011, "无法确定该请求所属用户，已拒绝使用平台默认凭证"),

    // ========== 平台级 AI 能力开关 14xxx ==========
    AI_PLATFORM_KEY_DISABLED(14001, "该 AI 功能正在改造中，暂不可用，请先在设置页配置自己的模型凭证");

    private final Integer code;
    private final String message;
}
