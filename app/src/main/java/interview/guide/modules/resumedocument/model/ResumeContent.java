package interview.guide.modules.resumedocument.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线结构化简历正文。
 *
 * <p>以 JSON 文本落库（见 {@code resume_documents.content}），这里使用可变 POJO 承载
 * 序列化/反序列化，便于提案按「稳定 ID」做增量修改；对外的请求体仍使用不可变 record。</p>
 *
 * <p>education / projects / highlights / internships / awards 每条记录都有稳定 id，
 * 不依赖数组下标；实习的 description 是历史字段，与新的 highlights 并存，
 * 加载时原样保留，展示时去重，合并必须由用户主动确认。</p>
 */
@Data
public class ResumeContent {

    private BasicInfo basicInfo = new BasicInfo();
    private List<EducationItem> education = new ArrayList<>();
    private List<ProjectItem> projects = new ArrayList<>();
    private List<String> skills = new ArrayList<>();
    private List<InternshipItem> internships = new ArrayList<>();
    private List<AwardItem> awards = new ArrayList<>();

    @Data
    public static class BasicInfo {
        private String name = "";
        private String phone = "";
        private String email = "";
        private String school = "";
        private String major = "";
        private String degree = "";
        private String targetPosition = "";
        private String githubUrl = "";
        private String blogUrl = "";
    }

    @Data
    public static class EducationItem {
        private String id = "";
        private String school = "";
        private String major = "";
        private String degree = "";
        private String startDate = "";
        private String endDate = "";
        private String description = "";
    }

    @Data
    public static class ProjectItem {
        private String id = "";
        private String name = "";
        private String url = "";
        private String summary = "";
        private List<String> techStack = new ArrayList<>();
        private List<HighlightItem> highlights = new ArrayList<>();
        private String startDate = "";
        private String endDate = "";
    }

    @Data
    public static class HighlightItem {
        private String id = "";
        private String content = "";
        private int sortOrder = 0;
    }

    @Data
    public static class InternshipItem {
        private String id = "";
        private String company = "";
        private String position = "";
        private String startDate = "";
        private String endDate = "";
        /** 旧版单段工作内容：必须完整保留，不因新增成果列表而丢失。 */
        private String description = "";
        /** 新版「工作与成果」逐条记录，与项目亮点共用稳定 ID 与 sortOrder。 */
        private List<HighlightItem> highlights = new ArrayList<>();
    }

    @Data
    public static class AwardItem {
        private String id = "";
        private String name = "";
        private String date = "";
        private String description = "";
    }
}
