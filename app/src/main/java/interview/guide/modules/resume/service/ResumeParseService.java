package interview.guide.modules.resume.service;

import interview.guide.infrastructure.file.ContentTypeDetectionService;
import interview.guide.infrastructure.file.DocumentParseService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.ResumeOcrService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析服务
 * 委托给通用的 DocumentParseService 处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseService {

    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService storageService;
    private final ResumeOcrService ocrService;

    /**
     * 解析上传的简历文件，提取文本内容
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 提取的文本内容
     */
    public String parseResume(MultipartFile file) {
        log.info("开始解析简历文件: {}", file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    /**
     * 解析字节数组形式的简历文件
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容
     */
    public String parseResume(byte[] fileBytes, String fileName) {
        log.info("开始解析简历文件（从字节数组）: {}", fileName);
        if (fileBytes == null || fileBytes.length == 0) {
            return "";
        }
        String mime = contentTypeDetectionService.detectContentType(fileBytes, fileName);
        if (ResumeOcrService.isImage(mime)) {
            return ocrService.recognize(fileBytes, mime);
        }
        String text = documentParseService.parseContent(fileBytes, fileName);
        return "application/pdf".equals(mime) && text.isBlank() ? ocrService.recognize(fileBytes, mime) : text;
    }

    /**
     * 从存储下载文件并解析内容
     *
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的文本内容
     */
    public String downloadAndParseContent(String storageKey, String originalFilename) {
        log.info("从存储下载并解析简历文件: {}", originalFilename);
        byte[] bytes = storageService.downloadFile(storageKey);
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法读取原始简历文件");
        }
        return parseResume(bytes, originalFilename);
    }

    /**
     * 检测文件的MIME类型
     */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }
}
