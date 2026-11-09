package interview.guide.modules.usercredential.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.usercredential.dto.CredentialStatusDTO;
import interview.guide.modules.usercredential.dto.CredentialTestResultDTO;
import interview.guide.modules.usercredential.dto.ProviderPresetDTO;
import interview.guide.modules.usercredential.dto.SaveCredentialRequest;
import interview.guide.modules.usercredential.dto.TestCredentialRequest;
import interview.guide.modules.usercredential.dto.UpdateCredentialRequest;
import interview.guide.modules.usercredential.dto.UserCredentialDTO;
import interview.guide.modules.usercredential.service.CredentialConnectionTestService;
import interview.guide.modules.usercredential.service.UserCredentialService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户的 AI 模型凭证管理。
 *
 * <p><b>所有接口的 userId 都从服务端认证上下文中获取</b>，
 * 请求体中不存在也不接受 userId 字段，因此用户 A 无法操作用户 B 的凭证。</p>
 */
@RestController
@RequestMapping("/api/me/ai-credentials")
@RequiredArgsConstructor
public class UserCredentialController {

    private final UserCredentialService userCredentialService;
    private final CredentialConnectionTestService connectionTestService;
    private final CurrentUserProvider currentUserProvider;

    /** 查询当前用户已配置的凭证列表（脱敏）。 */
    @GetMapping
    public Result<List<UserCredentialDTO>> list() {
        return Result.success(userCredentialService.list(currentUserProvider.requireUserId()));
    }

    /** 查询白名单内的供应商预设及配置状态。 */
    @GetMapping("/providers")
    public Result<List<ProviderPresetDTO>> providers() {
        return Result.success(
            userCredentialService.listProviders(currentUserProvider.requireUserId()));
    }

    /**
     * 当前用户 AI 能力就绪状态（Chat / 语音）。
     *
     * <p>前端据此展示"Chat 已就绪 / 语音不可用"等状态，不返回任何凭证内容。</p>
     */
    @GetMapping("/status")
    public Result<CredentialStatusDTO> status() {
        return Result.success(userCredentialService.status(currentUserProvider.requireUserId()));
    }

    /** 查询指定供应商的配置状态（脱敏）。 */
    @GetMapping("/{providerKey}")
    public Result<UserCredentialDTO> detail(@PathVariable String providerKey) {
        return Result.success(
            userCredentialService.get(currentUserProvider.requireUserId(), providerKey));
    }

    /** 新增或覆盖凭证。 */
    @PostMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<UserCredentialDTO> save(@Valid @RequestBody SaveCredentialRequest request) {
        return Result.success("配置已保存",
            userCredentialService.save(currentUserProvider.requireUserId(), request));
    }

    /** 更新凭证（Key 或模型名可单独更新）。 */
    @PutMapping("/{providerKey}")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 20, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<UserCredentialDTO> update(@PathVariable String providerKey,
                                            @Valid @RequestBody UpdateCredentialRequest request) {
        Long userId = currentUserProvider.requireUserId();
        // 复用保存逻辑：先读取现有配置，再按需合并字段，避免另一套写路径。
        UserCredentialDTO current = userCredentialService.get(userId, providerKey);
        if (!current.configured() && isBlank(request.apiKey())) {
            return Result.success("请提供 API Key 以完成首次配置",
                UserCredentialDTO.notConfigured(providerKey));
        }
        if (!isBlank(request.apiKey())) {
            // 有新的 Key：走完整保存（加密 + 版本递增）。
            String modelName = isBlank(request.modelName()) ? current.modelName() : request.modelName();
            return Result.success("配置已更新", userCredentialService.save(userId,
                new SaveCredentialRequest(providerKey, request.apiKey(), modelName)));
        }
        // 仅更新模型名：只改模型，不动密文。
        return Result.success("配置已更新",
            userCredentialService.updateModelName(userId, providerKey, request.modelName()));
    }

    /** 删除凭证。 */
    @DeleteMapping("/{providerKey}")
    public Result<Void> delete(@PathVariable String providerKey) {
        userCredentialService.delete(currentUserProvider.requireUserId(), providerKey);
        return Result.success("配置已删除", null);
    }

    /**
     * 使用「已保存」的用户凭证做真实连接测试。
     *
     * <p>会实际向供应商发起一次极轻量请求；失败时返回经过脱敏的错误信息。
     * 不返回、不记录 API Key，也<b>不会</b>使用开发者全局 Key。</p>
     */
    @PostMapping("/{providerKey}/test")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<CredentialTestResultDTO> test(@PathVariable String providerKey) {
        return Result.success(connectionTestService.testSaved(
            currentUserProvider.requireUserId(), providerKey));
    }

    /**
     * 使用「未保存」的临时凭证做真实连接测试（用于保存前先验证）。
     *
     * <p>凭证只在本次请求内存中存在，不写库、不写日志。</p>
     */
    @PostMapping("/test")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<CredentialTestResultDTO> testTransient(@Valid @RequestBody TestCredentialRequest request) {
        return Result.success(connectionTestService.testTransient(
            currentUserProvider.requireUserId(), request));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
