package interview.guide.modules.resumedocument.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.model.ResumeDocumentLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化简历正文的序列化、规范化与校验。
 *
 * <p>同时负责「由原始简历解析全文初始化结构化数据」——只做本地规则抽取，
 * 绝不重复调用大模型分析，符合上传链路零侵入的要求。</p>
 */
@Component
@RequiredArgsConstructor
public class ResumeContentCodec {

    /** 支持 13800000000 与 138-0000-1234 / 138 0000 1234 等常见分隔符写法。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d[\\s-]?\\d{4}[\\s-]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern GITHUB = Pattern.compile("https?://(?:www\\.)?github\\.com/[A-Za-z0-9._\\-/]+");
    private static final Pattern URL = Pattern.compile("https?://[^\\s，,；;）)]+");
    private static final Pattern DEGREE = Pattern.compile("(博士|硕士|研究生|本科|学士|大专|专科)");
    private static final Pattern MAJOR = Pattern.compile("专业\\s*[:：]?\\s*([^\\s，,；;|]{2,20})");
    private static final Pattern TARGET = Pattern.compile("(?:求职意向|应聘岗位|目标岗位|意向岗位|期望岗位)\\s*[:：]?\\s*([^\\n\\r，,；;|]{2,30})");
    private static final Pattern NAME_LINE = Pattern.compile("^[\\u4e00-\\u9fa5]{2,6}$|^[A-Za-z][A-Za-z .'-]{1,29}$");
    private static final List<String> NON_NAME_KEYWORDS = List.of(
        "简历", "个人", "求职", "姓名", "电话", "邮箱", "教育", "项目", "技能", "经历",
        "大学", "学院", "专业", "意向", "地址", "性别", "出生", "政治", "objective", "resume");
    /** “2019-09 / 2019.09 / 2019年9月” 形式的年月。 */
    private static final Pattern DATE_TOKEN = Pattern.compile("(\\d{4})\\s*[./年-]\\s*(\\d{1,2})\\s*月?");
    private static final Pattern SCHOOL_PREFIX =
        Pattern.compile("^(.*?(?:大学|学院|学校|University|College))(.*)$");
    private static final Pattern TECH_LABEL = Pattern.compile(
        "^(?:技术栈|技术关键词|关键词|使用技术|技术选型|开发技术|技术框架)\\s*[:：]?\\s*(.+)$");
    private static final Pattern SUMMARY_LABEL = Pattern.compile(
        "^(?:项目介绍|项目描述|项目简介|项目背景|项目概述|简介|概述)\\s*[:：]?\\s*(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-•·*・▪◦]|\\d{1,2}[.、)])\\s*");
    private static final Pattern INLINE_SKILL = Pattern.compile(
        "^(?:专业技能|技能清单|核心技能|技能特长|技术能力|技能)\\s*[:：]\\s*(.+)$");

    private final ObjectMapper objectMapper;

    public ResumeContent blank() {
        return new ResumeContent();
    }

    public String serialize(ResumeContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID, "简历数据序列化失败");
        }
    }

    public ResumeContent deserialize(String json) {
        if (json == null || json.isBlank()) {
            return blank();
        }
        try {
            ResumeContent parsed = objectMapper.readValue(json, ResumeContent.class);
            return parsed == null ? blank() : parsed;
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID, "简历数据解析失败");
        }
    }

    /**
     * 规范化：补齐空结构、为缺失/重复的记录生成稳定 UUID、重排技术亮点序号。
     * 任何来自前端或 AI 的内容在写库前都必须经过这里。
     */
    public ResumeContent normalize(ResumeContent raw) {
        ResumeContent content = raw == null ? blank() : raw;
        ResumeContent.BasicInfo basic = content.getBasicInfo() == null
            ? new ResumeContent.BasicInfo() : content.getBasicInfo();
        content.setBasicInfo(basic);
        normalizeShort(basic);

        Set<String> usedIds = new HashSet<>();
        content.setEducation(normalizeEducation(content.getEducation(), usedIds));
        content.setProjects(normalizeProjects(content.getProjects(), usedIds));
        content.setInternships(normalizeInternships(content.getInternships(), usedIds));
        content.setAwards(normalizeAwards(content.getAwards(), usedIds));
        content.setSkills(normalizeStrings(content.getSkills(),
            ResumeDocumentLimits.MAX_SKILLS, ResumeDocumentLimits.MAX_SHORT_TEXT, usedIds, false));
        return content;
    }

    /**
     * 内容合法性校验：数量、字符串长度、ID 唯一性。
     */
    public void validate(ResumeContent content) {
        if (content == null) {
            throw invalid("简历内容不能为空");
        }
        checkSize(content.getEducation().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "教育经历");
        checkSize(content.getProjects().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "项目经历");
        checkSize(content.getInternships().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "实习经历");
        checkSize(content.getAwards().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "获奖经历");
        checkSize(content.getSkills().size(), ResumeDocumentLimits.MAX_SKILLS, "专业技能");
        checkText(content.getBasicInfo().getName(), "姓名");
        checkText(content.getBasicInfo().getPhone(), "电话");
        checkText(content.getBasicInfo().getEmail(), "邮箱");
        checkText(content.getBasicInfo().getSchool(), "学校");
        checkText(content.getBasicInfo().getMajor(), "专业");
        checkText(content.getBasicInfo().getDegree(), "学历");
        checkText(content.getBasicInfo().getTargetPosition(), "求职意向");
        checkText(content.getBasicInfo().getGithubUrl(), "GitHub 链接");
        checkText(content.getBasicInfo().getBlogUrl(), "博客链接");
        for (String skill : content.getSkills()) {
            checkText(skill, "技能");
        }
        for (ResumeContent.EducationItem item : content.getEducation()) {
            checkText(item.getSchool(), "学校");
            checkText(item.getMajor(), "专业");
            checkText(item.getDescription(), "教育描述");
        }
        for (ResumeContent.ProjectItem item : content.getProjects()) {
            checkText(item.getName(), "项目名称");
            checkText(item.getUrl(), "项目链接");
            checkLongText(item.getSummary(), "项目介绍");
            checkSize(item.getTechStack().size(), ResumeDocumentLimits.MAX_LIST_ITEMS, "技术栈");
            checkSize(item.getHighlights().size(),
                ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT, "项目技术亮点");
            for (String tech : item.getTechStack()) {
                checkText(tech, "技术栈");
            }
            for (ResumeContent.HighlightItem highlight : item.getHighlights()) {
                checkLongText(highlight.getContent(), "技术亮点");
            }
        }
    }

    /**
     * 从已落库的 resume_text 做本地规则抽取，初始化结构化简历。
     * 不调用任何 LLM；先按章节分块再抽取字段，避免项目描述污染技能列表。
     * 抽取不到的字段留空，由用户通过 AI 对话或快速编辑补充。
     */
    public ResumeContent fromResumeText(String resumeText) {
        ResumeContent content = blank();
        if (resumeText == null || resumeText.isBlank()) {
            return content;
        }
        String text = resumeText.length() > 20_000 ? resumeText.substring(0, 20_000) : resumeText;
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        Map<Section, List<String>> sections = splitSections(lines);
        ResumeContent.BasicInfo basic = content.getBasicInfo();

        Matcher phone = PHONE.matcher(text);
        if (phone.find()) {
            basic.setPhone(phone.group());
        }
        Matcher email = EMAIL.matcher(text);
        if (email.find()) {
            basic.setEmail(email.group());
        }
        Matcher github = GITHUB.matcher(text);
        if (github.find()) {
            basic.setGithubUrl(github.group());
        } else {
            Matcher blog = URL.matcher(text);
            if (blog.find() && !blog.group().contains("github.com")) {
                basic.setBlogUrl(blog.group());
            }
        }
        Matcher target = TARGET.matcher(text);
        if (target.find()) {
            basic.setTargetPosition(clip(target.group(1).trim(), ResumeDocumentLimits.MAX_SHORT_TEXT));
        }
        for (String line : sections.get(Section.HEADER)) {
            if (basic.getName().isEmpty() && NAME_LINE.matcher(line).matches()
                && NON_NAME_KEYWORDS.stream().noneMatch(line::contains)) {
                basic.setName(line);
                break;
            }
        }

        List<ResumeContent.EducationItem> education = extractEducation(sections);
        content.setEducation(education);
        if (!education.isEmpty()) {
            ResumeContent.EducationItem first = education.get(0);
            basic.setSchool(first.getSchool());
            basic.setMajor(first.getMajor());
            basic.setDegree(first.getDegree());
        }
        if (basic.getDegree().isEmpty()) {
            Matcher degree = DEGREE.matcher(text);
            if (degree.find()) {
                basic.setDegree(degree.group());
            }
        }
        if (basic.getMajor().isEmpty()) {
            Matcher major = MAJOR.matcher(text);
            if (major.find()) {
                String candidate = major.group(1);
                if (!candidate.contains("技能") && !candidate.contains("简历")) {
                    basic.setMajor(clip(candidate, ResumeDocumentLimits.MAX_SHORT_TEXT));
                }
            }
        }
        content.setProjects(extractProjects(sections));
        content.setSkills(extractSkills(sections));
        return normalize(content);
    }

    /** 简历正文章节。实习、获奖等扩展位不由规则臆造，保留给表单与 AI 对话补全。 */
    private enum Section { HEADER, EDUCATION, PROJECT, SKILL }

    private Map<Section, List<String>> splitSections(List<String> lines) {
        Map<Section, List<String>> sections = new EnumMap<>(Section.class);
        for (Section section : Section.values()) {
            sections.put(section, new ArrayList<>());
        }
        Section current = Section.HEADER;
        for (String line : lines) {
            Section heading = detectSection(line);
            if (heading != null) {
                current = heading;
                continue;
            }
            // “专业技能：Java、Redis” 这类标题与内容同行的写法
            Matcher inlineSkill = INLINE_SKILL.matcher(line);
            if (inlineSkill.matches()) {
                current = Section.SKILL;
                sections.get(Section.SKILL).add(inlineSkill.group(1).trim());
                continue;
            }
            sections.get(current).add(line);
        }
        return sections;
    }

    /** 只把独立的短标题行识别为章节，避免把“技术栈：xxx”或项目名误判为章节标题。 */
    private Section detectSection(String line) {
        String heading = line.replaceAll("[\\s:：|丨]+$", "").trim();
        if (heading.isEmpty() || heading.length() > 12) {
            return null;
        }
        if (DATE_TOKEN.matcher(heading).find() || PHONE.matcher(heading).find()) {
            return null;
        }
        if (heading.matches("^(?:教育|教育经历|教育背景|教育情况|学习经历)$")) {
            return Section.EDUCATION;
        }
        if (heading.matches("^(?:项目|项目经历|项目经验|项目实践|项目列表|个人项目|实践经历)$")) {
            return Section.PROJECT;
        }
        if (heading.matches("^(?:技能|专业技能|技能清单|核心技能|技能特长|技术栈|技术能力)$")) {
            return Section.SKILL;
        }
        return null;
    }

    private List<ResumeContent.EducationItem> extractEducation(Map<Section, List<String>> sections) {
        List<String> candidates = new ArrayList<>(sections.get(Section.EDUCATION));
        if (candidates.isEmpty()) {
            for (String line : sections.get(Section.HEADER)) {
                if ((line.contains("大学") || line.contains("学院")) && line.length() <= 60) {
                    candidates.add(line);
                }
            }
        }
        List<ResumeContent.EducationItem> items = new ArrayList<>();
        for (String line : candidates) {
            ResumeContent.EducationItem item = parseEducationLine(line);
            if (item != null) {
                items.add(item);
            }
            if (items.size() >= 5) {
                break;
            }
        }
        // 「深圳大学 计算机与软件学院」这种把学院名单独成行的写法，解析不出真实专业；
        // 再从「计算机科学与技术 本科 · GPA…」这类学位行里补一次
        if (!items.isEmpty() && items.get(0).getMajor().isBlank()) {
            String fallback = findMajorFromDegreeLine(sections.get(Section.EDUCATION));
            if (!fallback.isEmpty()) {
                items.get(0).setMajor(clip(fallback, ResumeDocumentLimits.MAX_SHORT_TEXT));
            }
        }
        return items;
    }

    /** 从「<专业> 本科 / 硕士 …」的学位行里取专业名。 */
    private String findMajorFromDegreeLine(List<String> lines) {
        for (String line : lines) {
            Matcher degree = DEGREE.matcher(line);
            if (!degree.find()) {
                continue;
            }
            String head = line.substring(0, degree.start())
                .replaceAll("^.*?(?:大学|学院)\\s*", "")
                .replaceAll("^[\\s·|丨,，、:：]+|[\\s·|丨,，、:：]+$", "")
                .trim();
            if (head.length() >= 2 && head.length() <= 20
                && head.matches("[\\u4e00-\\u9fa5A-Za-z（）() ]+")) {
                return head;
            }
        }
        return "";
    }

    /** “大连理工大学 计算机科学与技术 本科 2019-09 至 2023-06” 拆为学校/专业/学位/起止时间。 */
    private ResumeContent.EducationItem parseEducationLine(String line) {
        String[] dates = extractDates(line);
        String degree = "";
        Matcher degreeMatcher = DEGREE.matcher(line);
        if (degreeMatcher.find()) {
            degree = degreeMatcher.group();
        }
        String rest = DEGREE.matcher(removeDates(line)).replaceAll(" ");
        String school = "";
        String major = "";
        for (String token : rest.split("[\\s|丨、,，/]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (school.isEmpty() && token.matches(".*(?:大学|学院|学校|University|College).*")) {
                // “清华大学计算机科学与技术”这类无空格写法：学校名到“大学/学院”为止
                Matcher matcher = SCHOOL_PREFIX.matcher(token);
                if (matcher.matches()) {
                    school = matcher.group(1);
                    major = cleanMajor(matcher.group(2));
                } else {
                    school = token;
                }
            } else if (major.isEmpty()) {
                major = cleanMajor(token);
            }
        }
        if (school.isBlank()) {
            return null;
        }
        ResumeContent.EducationItem item = new ResumeContent.EducationItem();
        item.setId(UUID.randomUUID().toString());
        item.setSchool(clip(school, ResumeDocumentLimits.MAX_SHORT_TEXT));
        item.setMajor(clip(major, ResumeDocumentLimits.MAX_SHORT_TEXT));
        item.setDegree(degree);
        if (dates != null) {
            item.setStartDate(dates[0]);
            item.setEndDate(dates[1]);
        }
        return item;
    }

    private String cleanMajor(String token) {
        if (token == null) {
            return "";
        }
        String major = token.replaceFirst("^专业[:：]?", "").replaceAll("专业$", "").trim();
        // 「计算机与软件学院」是学院名，不是专业；留空后由学位行兜底补全
        if (major.matches(".*(?:大学|学院|学校|University|College).*")) {
            return "";
        }
        return major.length() >= 2 && !major.matches(".*\\d.*") ? major : "";
    }

    private List<ResumeContent.ProjectItem> extractProjects(Map<Section, List<String>> sections) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : sections.get(Section.PROJECT)) {
            if (isProjectNameLine(line)) {
                current = new ArrayList<>();
                blocks.add(current);
            }
            if (current != null) {
                current.add(line);
            }
        }
        List<ResumeContent.ProjectItem> projects = new ArrayList<>();
        for (List<String> block : blocks) {
            ResumeContent.ProjectItem item = parseProjectBlock(block);
            if (item != null) {
                projects.add(item);
            }
            if (projects.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                break;
            }
        }
        return projects;
    }

    /** 项目名通常是短行且不带句读；含“技术栈：”“亮点一：”等标签或句号的行属于项目内容。 */
    private boolean isProjectNameLine(String line) {
        if (line.length() > 40 || BULLET.matcher(line).find()) {
            return false;
        }
        if (line.matches(".*[，。；：:].*")) {
            return false;
        }
        return !line.matches("^(?:亮点|项目亮点|技术亮点|职责|负责|主要工作|项目职责|工作职责|成果|收获"
            + "|技术栈|使用技术|项目介绍|项目描述).*");
    }

    private ResumeContent.ProjectItem parseProjectBlock(List<String> block) {
        ResumeContent.ProjectItem item = new ResumeContent.ProjectItem();
        item.setId(UUID.randomUUID().toString());
        String nameLine = block.get(0);
        String[] dates = extractDates(nameLine);
        item.setName(clip(removeDates(nameLine).replaceAll("[【】\\[\\]]", "").trim(),
            ResumeDocumentLimits.MAX_SHORT_TEXT));
        if (dates != null) {
            item.setStartDate(dates[0]);
            item.setEndDate(dates[1]);
        }
        List<String> techStack = new ArrayList<>();
        List<String> highlights = new ArrayList<>();
        String summary = "";
        for (int i = 1; i < block.size(); i++) {
            String line = block.get(i);
            Matcher techMatcher = TECH_LABEL.matcher(line);
            if (techMatcher.matches()) {
                for (String part : techMatcher.group(1).split("[、,，;；|/]+")) {
                    String tech = clip(part.trim(), ResumeDocumentLimits.MAX_SHORT_TEXT);
                    if (!tech.isEmpty()) {
                        techStack.add(tech);
                    }
                }
                continue;
            }
            Matcher summaryMatcher = SUMMARY_LABEL.matcher(line);
            if (summaryMatcher.matches()) {
                summary = clip(summaryMatcher.group(1).trim(), 600);
                continue;
            }
            boolean bullet = BULLET.matcher(line).find();
            String cleaned = BULLET.matcher(line).replaceFirst("").trim()
                .replaceFirst("^(?:项目亮点|技术亮点|亮点)\\s*[0-9零一二三四五六七八九十]*\\s*[:：]?\\s*", "")
                .trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (summary.isEmpty() && highlights.isEmpty() && !bullet
                && !line.matches("^(?:亮点|项目亮点|技术亮点|负责|职责|主要工作|成果).*")) {
                // 项目名之后的第一句普通描述视为项目介绍，其余内容进入亮点列表
                summary = clip(cleaned, 600);
            } else {
                highlights.add(clip(cleaned, ResumeDocumentLimits.MAX_LONG_TEXT));
            }
        }
        // 只有项目名称、没有任何实质内容时不臆造项目条目，交由 AI 对话或表单补全
        if (item.getName().isEmpty()
            || (techStack.isEmpty() && summary.isEmpty() && highlights.isEmpty())) {
            return null;
        }
        item.setTechStack(techStack.stream().distinct().limit(20).toList());
        item.setSummary(summary);
        List<ResumeContent.HighlightItem> highlightItems = new ArrayList<>();
        for (String highlight : highlights) {
            if (highlightItems.size() >= ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT) {
                break;
            }
            ResumeContent.HighlightItem highlightItem = new ResumeContent.HighlightItem();
            highlightItem.setId(UUID.randomUUID().toString());
            highlightItem.setContent(highlight);
            highlightItems.add(highlightItem);
        }
        item.setHighlights(highlightItems);
        return item;
    }

    /** 抽取 “2019-09 至 2023-06 / 2021.03-至今” 中的起止时间，返回 {start, end}。 */
    private String[] extractDates(String line) {
        Matcher matcher = DATE_TOKEN.matcher(line);
        List<String> dates = new ArrayList<>();
        while (matcher.find()) {
            dates.add(String.format("%s-%02d", matcher.group(1), Integer.parseInt(matcher.group(2))));
        }
        if (dates.size() >= 2) {
            return new String[] {dates.get(0), dates.get(1)};
        }
        if (dates.size() == 1 && line.matches(".*(?:至今|现在|今).*")) {
            return new String[] {dates.get(0), "至今"};
        }
        return null;
    }

    private String removeDates(String line) {
        String cleaned = line.replaceAll("[（(][^）)]*\\d{4}[^）)]*[）)]", " ");
        cleaned = DATE_TOKEN.matcher(cleaned).replaceAll(" ");
        return cleaned.replaceAll("[\\s至到~\\-–—|丨]+$", "").trim();
    }

    private List<String> extractSkills(Map<Section, List<String>> sections) {
        List<String> skills = new ArrayList<>();
        for (String line : sections.get(Section.SKILL)) {
            collectSkills(line, skills);
            if (skills.size() >= ResumeDocumentLimits.MAX_SKILLS) {
                break;
            }
        }
        if (skills.isEmpty()) {
            for (String line : sections.get(Section.HEADER)) {
                if (line.matches(".*(?:熟悉|精通|掌握|了解).*") && line.contains("：")) {
                    collectSkills(line.substring(line.indexOf("：") + 1), skills);
                }
            }
        }
        return skills.stream().distinct().limit(ResumeDocumentLimits.MAX_SKILLS).toList();
    }

    private void collectSkills(String line, List<String> skills) {
        String cleaned = BULLET.matcher(line).replaceFirst("").trim();
        if (cleaned.isEmpty()) {
            return;
        }
        // 短行且是纯枚举时按顿号/逗号拆成多条；成句的技能描述（含分号、句号或较长）
        // 必须整句保留，否则「熟悉集合、并发、JVM 内存模型」会被切成三个没意义的碎片
        boolean enumeration = cleaned.length() <= 40
            && !cleaned.matches(".*[。；;！!]$")
            && !cleaned.contains("；") && !cleaned.contains(";");
        if (enumeration) {
            for (String part : cleaned.split("[、,，|/]+")) {
                addSkill(part, skills);
            }
        } else {
            for (String sentence : cleaned.split("[。；;]+")) {
                addSkill(sentence, skills);
            }
        }
    }

    private void addSkill(String raw, List<String> skills) {
        String skill = clip(raw.replaceAll("^[\\s·•\\-、,，:：]+|[\\s、,，。；;:：]+$", ""),
            ResumeDocumentLimits.MAX_SHORT_TEXT);
        if (skill.length() >= 2 && skills.size() < ResumeDocumentLimits.MAX_SKILLS) {
            skills.add(skill);
        }
    }

    private void normalizeShort(ResumeContent.BasicInfo basic) {
        basic.setName(text(basic.getName()));
        basic.setPhone(text(basic.getPhone()));
        basic.setEmail(text(basic.getEmail()));
        basic.setSchool(text(basic.getSchool()));
        basic.setMajor(text(basic.getMajor()));
        basic.setDegree(text(basic.getDegree()));
        basic.setTargetPosition(text(basic.getTargetPosition()));
        basic.setGithubUrl(text(basic.getGithubUrl()));
        basic.setBlogUrl(text(basic.getBlogUrl()));
    }

    private List<ResumeContent.EducationItem> normalizeEducation(
        List<ResumeContent.EducationItem> source, Set<String> usedIds) {
        List<ResumeContent.EducationItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.EducationItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setSchool(text(item.getSchool()));
            item.setMajor(text(item.getMajor()));
            item.setDegree(text(item.getDegree()));
            item.setStartDate(text(item.getStartDate()));
            item.setEndDate(text(item.getEndDate()));
            item.setDescription(longText(item.getDescription()));
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.ProjectItem> normalizeProjects(
        List<ResumeContent.ProjectItem> source, Set<String> usedIds) {
        List<ResumeContent.ProjectItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.ProjectItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setName(text(item.getName()));
            item.setUrl(text(item.getUrl()));
            item.setSummary(longText(item.getSummary()));
            item.setStartDate(text(item.getStartDate()));
            item.setEndDate(text(item.getEndDate()));
            item.setTechStack(normalizeStrings(item.getTechStack(),
                ResumeDocumentLimits.MAX_LIST_ITEMS, ResumeDocumentLimits.MAX_SHORT_TEXT, null, false));
            item.setHighlights(normalizeHighlights(item.getHighlights(), usedIds));
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.HighlightItem> normalizeHighlights(
        List<ResumeContent.HighlightItem> source, Set<String> usedIds) {
        List<ResumeContent.HighlightItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.HighlightItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setContent(longText(item.getContent()));
            item.setSortOrder(result.size() + 1);
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.InternshipItem> normalizeInternships(
        List<ResumeContent.InternshipItem> source, Set<String> usedIds) {
        List<ResumeContent.InternshipItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.InternshipItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setCompany(text(item.getCompany()));
            item.setPosition(text(item.getPosition()));
            item.setStartDate(text(item.getStartDate()));
            item.setEndDate(text(item.getEndDate()));
            item.setDescription(longText(item.getDescription()));
            result.add(item);
        }
        return result;
    }

    private List<ResumeContent.AwardItem> normalizeAwards(
        List<ResumeContent.AwardItem> source, Set<String> usedIds) {
        List<ResumeContent.AwardItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (ResumeContent.AwardItem item : source) {
            if (item == null || result.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                continue;
            }
            item.setId(stableId(item.getId(), usedIds));
            item.setName(text(item.getName()));
            item.setDate(text(item.getDate()));
            item.setDescription(longText(item.getDescription()));
            result.add(item);
        }
        return result;
    }

    private List<String> normalizeStrings(List<String> source, int maxSize, int maxLength,
                                          Set<String> usedIds, boolean deduplicate) {
        List<String> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (String value : source) {
            if (result.size() >= maxSize) {
                break;
            }
            String normalized = clip(text(value), maxLength);
            if (normalized.isEmpty() || (deduplicate && !seen.add(normalized))) {
                continue;
            }
            result.add(normalized);
        }
        return result;
    }

    private String stableId(String raw, Set<String> usedIds) {
        String id = raw == null ? "" : raw.trim();
        if (id.isEmpty() || id.length() > 64 || !usedIds.add(id)) {
            id = UUID.randomUUID().toString();
            while (!usedIds.add(id)) {
                id = UUID.randomUUID().toString();
            }
        }
        return id;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String longText(String value) {
        return value == null ? "" : value;
    }

    private String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private void checkSize(int actual, int max, String label) {
        if (actual > max) {
            throw invalid(label + "数量超过上限 " + max);
        }
    }

    private void checkText(String value, String label) {
        if (value != null && value.length() > ResumeDocumentLimits.MAX_SHORT_TEXT) {
            throw invalid(label + "长度超过上限 " + ResumeDocumentLimits.MAX_SHORT_TEXT);
        }
    }

    private void checkLongText(String value, String label) {
        if (value != null && value.length() > ResumeDocumentLimits.MAX_LONG_TEXT) {
            throw invalid(label + "长度超过上限 " + ResumeDocumentLimits.MAX_LONG_TEXT);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID, message);
    }
}
