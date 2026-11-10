package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

@Service
public class ResumeOcrService {
    private static final int MAX_PAGES = 10;
    private static final int MAX_EDGE = 2400;
    private static final long MAX_IMAGE_PIXELS = 25_000_000L;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final int TOTAL_TIMEOUT_SECONDS = 40;
    private final Semaphore workers = new Semaphore(2);
    private final TextCleaningService cleaner;

    public ResumeOcrService(TextCleaningService cleaner) {
        this.cleaner = cleaner;
    }

    public static boolean isImage(String mime) {
        return "image/jpeg".equals(mime) || "image/png".equals(mime);
    }

    public String recognize(byte[] bytes, String mime) {
        boolean acquired = false;
        Path dir = null;
        try {
            acquired = workers.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw failure("图片识别正在处理其他简历，请稍后重试");
            }
            dir = Files.createTempDirectory("interview-resume-ocr-");
            Path input = dir.resolve("input");
            Path output = dir.resolve("text");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOTAL_TIMEOUT_SECONDS);
            String text;
            if ("application/pdf".equals(mime)) {
                text = recognizePdf(bytes, input, output, deadline);
            } else if (isImage(mime)) {
                validateImage(bytes);
                Files.write(input, bytes);
                text = runOcr(input, output, deadline);
            } else {
                throw failure("此文件格式不能进行图片文字识别");
            }
            text = cleaner.cleanText(text);
            if (text.isBlank()) {
                throw failure("未识别到简历文字，请上传更清晰的图片或包含文本层的 PDF");
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("图片识别已中断，请重试");
        } catch (IOException e) {
            throw failure("无法读取图片或扫描 PDF，请检查文件是否损坏");
        } finally {
            if (dir != null) {
                delete(dir.resolve("input"));
                delete(dir.resolve("text"));
                delete(dir);
            }
            if (acquired) {
                workers.release();
            }
        }
    }

    private String recognizePdf(byte[] bytes, Path input, Path output, long deadline)
            throws IOException, InterruptedException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes), MemoryUsageSetting.setupTempFileOnly())) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw failure("此 PDF 禁止提取内容，请上传可读取的版本");
            }
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw failure("扫描 PDF 最多支持 " + MAX_PAGES + " 页，请拆分后重试");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                if (System.nanoTime() >= deadline) {
                    throw failure("图片识别超时，请拆分 PDF 后重试");
                }
                var box = document.getPage(index).getCropBox();
                float edge = Math.max(box.getWidth(), box.getHeight());
                if (!Float.isFinite(edge) || edge <= 0 || box.getWidth() <= 0 || box.getHeight() <= 0) {
                    throw failure("PDF 页面尺寸无效");
                }
                float scale = Math.min(200f / 72f, MAX_EDGE / edge);
                var image = renderer.renderImage(index, scale, ImageType.RGB);
                try {
                    ImageIO.write(image, "png", input.toFile());
                    result.append(runOcr(input, output, deadline)).append('\n');
                } finally {
                    image.flush();
                }
            }
            return result.toString();
        }
    }

    private void validateImage(byte[] bytes) throws IOException {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) {
                throw failure("无法读取图片");
            }
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw failure("无法读取图片，请确认是有效的 JPG 或 PNG");
            }
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw failure("图片尺寸过大，请缩小后重试");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private String runOcr(Path input, Path output, long deadline) throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw failure("图片识别超时，请上传更清晰的图片或减少 PDF 页数");
        }
        ProcessBuilder command = new ProcessBuilder("tesseract", input.toString(), "stdout",
            "-l", "chi_sim+eng", "--psm", "3").redirectOutput(output.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        command.environment().put("OMP_THREAD_LIMIT", "1");
        Process process;
        try {
            process = command.start();
        } catch (IOException e) {
            throw failure("服务器图片识别暂不可用，请稍后重试或上传带文本层的 PDF");
        }
        try {
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                throw failure("图片识别超时，请上传更清晰的图片或减少 PDF 页数");
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_OUTPUT_BYTES) {
                throw failure("图片识别失败，请检查文件格式与清晰度");
            }
            return Files.readString(output, StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static BusinessException failure(String message) {
        return new BusinessException(ErrorCode.RESUME_PARSE_FAILED, message);
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
        }
    }
}
