package interview.guide.modules.resumedocument;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.infrastructure.export.ResumePdfExportService;
import interview.guide.infrastructure.export.ResumePdfExportService.ResumeView;
import interview.guide.modules.resumedocument.model.ApplyProposalRequest;
import interview.guide.modules.resumedocument.model.CreateResumeDocumentRequest;
import interview.guide.modules.resumedocument.model.InitFromResumeRequest;
import interview.guide.modules.resumedocument.model.ResumeChatRequest;
import interview.guide.modules.resumedocument.model.ResumeDocumentDTO;
import interview.guide.modules.resumedocument.model.ResumeEditProposalDTO;
import interview.guide.modules.resumedocument.model.UpdateResumeDocumentRequest;
import interview.guide.modules.resumedocument.service.ResumeDocumentAiService;
import interview.guide.modules.resumedocument.service.ResumeDocumentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线简历文档接口。所有路径都在 /api/** 下，由 SecurityConfig 统一要求 JWT 登录，
 * 用户身份只从 SecurityContext 获取，所有数据访问在 Service 层带 user_id 隔离。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "在线简历文档", description = "在线简历创建、编辑、AI 对话修改提案与确认")
public class ResumeDocumentController {

    private final ResumeDocumentService documentService;
    private final ResumeDocumentAiService aiService;
    private final ResumePdfExportService pdfExportService;

    /** 从当前编辑快照生成 PDF，无需保存或读取其他简历。 */
    @PostMapping("/api/resume-documents/export/pdf")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 12, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public ResponseEntity<byte[]> exportPdf(@Valid @RequestBody ResumeView view) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore()).body(pdfExportService.export(view));
    }

    /** 当前用户的在线简历列表（供简历管理主页判断哪些原始简历已有可编辑版本）。 */
    @GetMapping("/api/resume-documents")
    public Result<List<ResumeDocumentDTO>> list() {
        return Result.success(documentService.list());
    }

    /** 新建空白简历。 */
    @PostMapping("/api/resume-documents")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 20, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<ResumeDocumentDTO> createBlank(
        @Valid @RequestBody CreateResumeDocumentRequest request) {
        return Result.success(documentService.createBlank(request.title()));
    }

    /** 由原始上传简历初始化在线编辑版本（幂等）。 */
    @PostMapping("/api/resume-documents/from-resume")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 20, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<ResumeDocumentDTO> initFromResume(
        @Valid @RequestBody InitFromResumeRequest request) {
        return Result.success(documentService.initializeFromResume(
            request.resumeId(), request.title()));
    }

    @GetMapping("/api/resume-documents/{id}")
    public Result<ResumeDocumentDTO> get(@PathVariable Long id) {
        return Result.success(documentService.get(id));
    }

    /** 保存已确认的简历状态（自动保存 / 基本信息修改），带乐观锁版本校验。 */
    @PutMapping("/api/resume-documents/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 90, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<ResumeDocumentDTO> update(
        @PathVariable Long id, @Valid @RequestBody UpdateResumeDocumentRequest request) {
        return Result.success(documentService.update(id, request));
    }

    @DeleteMapping("/api/resume-documents/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.success(null);
    }

    /** AI 对话：只返回修改提案，不写库；用户未配置凭证时返回 13001。 */
    @PostMapping("/api/resume-documents/{id}/chat")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 12, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 30, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<ResumeEditProposalDTO> chat(
        @PathVariable Long id, @Valid @RequestBody ResumeChatRequest request) {
        return Result.success(aiService.generate(id, request));
    }

    /** 用户确认修改：后端重新校验身份、版本与提案后才落库。 */
    @PostMapping("/api/resume-documents/{id}/proposals/apply")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 30, interval = 1,
        timeUnit = RateLimit.TimeUnit.MINUTES)
    public Result<ResumeDocumentDTO> applyProposal(
        @PathVariable Long id, @Valid @RequestBody ApplyProposalRequest request) {
        return Result.success(documentService.applyProposal(id, request));
    }
}
