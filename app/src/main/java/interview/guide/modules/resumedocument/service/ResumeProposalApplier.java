package interview.guide.modules.resumedocument.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.model.ResumeDocumentLimits;
import interview.guide.modules.resumedocument.model.ResumeEditOperationDTO;
import interview.guide.modules.resumedocument.model.ResumeEditProposalDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AI 修改提案的校验与应用。
 *
 * <p>AI 只负责产出增量操作；本组件是唯一能把提案变成数据库内容的入口：
 * 生成提案后会先 validate 一次，用户确认时再结合最新简历内容与版本号重新
 * validate 并应用。任何非法操作都会被拒绝，绝不整份覆盖。</p>
 */
@Service
@RequiredArgsConstructor
public class ResumeProposalApplier {

    private static final Set<String> OPS = Set.of("ADD", "UPDATE", "DELETE", "REORDER");
    private static final Set<String> TARGETS = Set.of(
        "BASIC_INFO", "EDUCATION", "PROJECT", "HIGHLIGHT", "SKILL",
        "INTERNSHIP", "INTERNSHIP_HIGHLIGHT");
    private static final Set<String> BASIC_INFO_FIELDS = Set.of(
        "name", "phone", "email", "school", "major", "degree",
        "targetPosition", "githubUrl", "blogUrl");
    private static final Set<String> EDUCATION_FIELDS = Set.of(
        "school", "major", "degree", "startDate", "endDate", "description");
    private static final Set<String> PROJECT_FIELDS = Set.of(
        "name", "url", "summary", "techStack", "startDate", "endDate");
    private static final Set<String> HIGHLIGHT_FIELDS = Set.of("content", "sortOrder");
    private static final Set<String> INTERNSHIP_FIELDS = Set.of(
        "company", "position", "startDate", "endDate", "description");
    private static final int MAX_SUMMARY_ITEMS = 20;
    private static final int MAX_SUMMARY_LENGTH = 300;

    private final ResumeContentCodec contentCodec;

    /**
     * 只校验、不改动传入对象。AI 生成提案后与用户确认保存前都会调用。
     */
    public void validate(ResumeContent current, ResumeEditProposalDTO proposal) {
        if (proposal == null) {
            throw invalid("修改提案不能为空");
        }
        checkLength(proposal.reply(), ResumeDocumentLimits.MAX_MESSAGE_LENGTH, "回复");
        checkLength(proposal.clarificationQuestion(), 1000, "追问问题");
        List<String> summaries = proposal.changeSummary();
        if (summaries != null) {
            if (summaries.size() > MAX_SUMMARY_ITEMS) {
                throw invalid("修改说明条目过多");
            }
            for (String summary : summaries) {
                checkLength(summary, MAX_SUMMARY_LENGTH, "修改说明");
            }
        }
        List<ResumeEditOperationDTO> operations = proposal.operations();
        if (operations == null || operations.isEmpty()) {
            if (!proposal.needsClarification()) {
                throw invalid("提案不包含任何修改操作");
            }
            return;
        }
        if (proposal.needsClarification()) {
            throw invalid("需要补充信息的提案不能同时包含修改操作");
        }
        if (operations.size() > ResumeDocumentLimits.MAX_OPERATIONS) {
            throw invalid("单次修改操作过多，请拆分成多次描述");
        }

        ResumeContent reference = deepCopy(current);
        Set<String> knownIds = collectIds(reference);
        Set<String> aliases = new HashSet<>();
        for (ResumeEditOperationDTO operation : operations) {
            boolean addRecord = "ADD".equals(upper(operation.op()))
                && ("PROJECT".equals(upper(operation.target()))
                    || "INTERNSHIP".equals(upper(operation.target())));
            if (addRecord && operation.id() != null && !operation.id().isBlank()) {
                aliases.add(operation.id());
            }
        }
        for (ResumeEditOperationDTO operation : operations) {
            validateOperation(operation, reference, knownIds, aliases);
        }
    }

    /**
     * 校验并把提案应用到当前简历的深拷贝上，返回新的结构化内容。
     */
    public ResumeContent apply(ResumeContent current, ResumeEditProposalDTO proposal) {
        validate(current, proposal);
        ResumeContent target = deepCopy(current);
        Map<String, String> generatedIds = new HashMap<>();
        List<ResumeEditOperationDTO> operations = proposal.operations() == null
            ? List.of() : proposal.operations();
        for (ResumeEditOperationDTO operation : operations) {
            applyOperation(target, operation, generatedIds);
        }
        return contentCodec.normalize(target);
    }

    private ResumeContent deepCopy(ResumeContent source) {
        ResumeContent normalized = contentCodec.normalize(source);
        return contentCodec.deserialize(contentCodec.serialize(normalized));
    }

    private void validateOperation(ResumeEditOperationDTO operation, ResumeContent reference,
                                   Set<String> knownIds, Set<String> aliases) {
        String op = upper(operation.op());
        String target = upper(operation.target());
        if (!OPS.contains(op) || !TARGETS.contains(target)) {
            throw invalid("包含不支持的操作类型或目标");
        }
        checkLength(operation.id(), 64, "记录 ID");
        checkLength(operation.parentId(), 64, "所属项目 ID");
        checkLength(operation.field(), 64, "字段名");
        checkLength(operation.value(), ResumeDocumentLimits.MAX_LONG_TEXT, "字段值");
        List<String> values = operation.values();
        if (values != null) {
            if (values.size() > ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT) {
                throw invalid("列表项过多");
            }
            for (String value : values) {
                checkLength(value, ResumeDocumentLimits.MAX_LONG_TEXT, "列表项");
            }
        }
        checkPatch(operation);

        if ("BASIC_INFO".equals(target)) {
            if (!"UPDATE".equals(op)) {
                throw invalid("个人信息只支持修改，不支持新增或删除");
            }
            if (!BASIC_INFO_FIELDS.contains(operation.field())) {
                throw invalid("包含不支持的个人信息字段");
            }
            if (operation.value() == null) {
                throw invalid("个人信息修改缺少新值");
            }
            return;
        }

        if ("SKILL".equals(target)) {
            if (values == null || values.isEmpty()) {
                throw invalid("技能修改缺少内容");
            }
            if ("REORDER".equals(op) && !isPermutation(values, reference.getSkills())) {
                throw invalid("技能顺序不是现有技能的排列");
            }
            return;
        }

        if ("ADD".equals(op)) {
            if ("HIGHLIGHT".equals(target) || "INTERNSHIP_HIGHLIGHT".equals(target)) {
                String parentId = operation.parentId();
                if (parentId == null || parentId.isBlank()
                    || (!knownIds.contains(parentId) && !aliases.contains(parentId))) {
                    throw invalid("新增亮点缺少有效的所属项目或实习");
                }
                if (isBlank(operation.value()) && isBlank(highlightContent(operation))) {
                    throw invalid("新增亮点内容为空");
                }
            } else {
                if ("EDUCATION".equals(target) && operation.education() == null) {
                    throw invalid("新增教育经历缺少内容");
                }
                if ("PROJECT".equals(target) && operation.project() == null) {
                    throw invalid("新增项目缺少内容");
                }
                if ("INTERNSHIP".equals(target) && operation.internship() == null) {
                    throw invalid("新增实习经历缺少内容");
                }
            }
            return;
        }

        if ("REORDER".equals(op)) {
            if (!"HIGHLIGHT".equals(target) && !"INTERNSHIP_HIGHLIGHT".equals(target)) {
                throw invalid("只有项目亮点、实习成果与技能支持调整顺序");
            }
            String parentId = operation.parentId();
            List<ResumeContent.HighlightItem> highlights = findHighlightList(reference, parentId);
            if (highlights == null && !aliases.contains(parentId)) {
                throw invalid("调整顺序缺少有效的所属项目或实习");
            }
            if (values == null || values.isEmpty()) {
                throw invalid("调整顺序缺少新的顺序");
            }
            if (highlights != null && !isPermutation(values,
                highlights.stream().map(ResumeContent.HighlightItem::getId).toList())) {
                throw invalid("亮点顺序不是现有亮点的排列");
            }
            return;
        }

        String id = operation.id();
        if (id == null || id.isBlank() || !knownIds.contains(id)) {
            throw invalid("修改或删除的目标记录不存在");
        }
        if ("UPDATE".equals(op)) {
            Set<String> fields = switch (target) {
                case "EDUCATION" -> EDUCATION_FIELDS;
                case "PROJECT" -> PROJECT_FIELDS;
                case "INTERNSHIP" -> INTERNSHIP_FIELDS;
                case "HIGHLIGHT", "INTERNSHIP_HIGHLIGHT" -> HIGHLIGHT_FIELDS;
                default -> Set.of();
            };
            if (operation.field() != null && !operation.field().isBlank()) {
                if (!fields.contains(operation.field())) {
                    throw invalid("包含不支持的修改字段");
                }
                if (operation.value() == null && operation.values() == null) {
                    throw invalid("单字段修改缺少新值");
                }
            }
        }
    }

    private void checkPatch(ResumeEditOperationDTO operation) {
        if (operation.education() != null) {
            var patch = operation.education();
            checkLength(patch.school(), ResumeDocumentLimits.MAX_SHORT_TEXT, "学校");
            checkLength(patch.description(), ResumeDocumentLimits.MAX_LONG_TEXT, "教育描述");
        }
        if (operation.project() != null) {
            var patch = operation.project();
            checkLength(patch.name(), ResumeDocumentLimits.MAX_SHORT_TEXT, "项目名称");
            checkLength(patch.summary(), ResumeDocumentLimits.MAX_LONG_TEXT, "项目介绍");
            if (patch.techStack() != null && patch.techStack().size() > ResumeDocumentLimits.MAX_LIST_ITEMS) {
                throw invalid("项目技术栈过多");
            }
            if (patch.highlights() != null
                && patch.highlights().size() > ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT) {
                throw invalid("项目技术亮点过多");
            }
            if (patch.highlights() != null) {
                for (String highlight : patch.highlights()) {
                    checkLength(highlight, ResumeDocumentLimits.MAX_LONG_TEXT, "技术亮点");
                }
            }
        }
        if (operation.highlight() != null) {
            checkLength(operation.highlight().content(), ResumeDocumentLimits.MAX_LONG_TEXT, "技术亮点");
        }
        if (operation.internship() != null) {
            var patch = operation.internship();
            checkLength(patch.company(), ResumeDocumentLimits.MAX_SHORT_TEXT, "公司名称");
            checkLength(patch.position(), ResumeDocumentLimits.MAX_SHORT_TEXT, "实习职位");
            checkLength(patch.description(), ResumeDocumentLimits.MAX_LONG_TEXT, "实习工作内容");
            if (patch.highlights() != null) {
                if (patch.highlights().size() > ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_INTERNSHIP) {
                    throw invalid("实习工作与成果过多");
                }
                for (String highlight : patch.highlights()) {
                    checkLength(highlight, ResumeDocumentLimits.MAX_LONG_TEXT, "工作与成果");
                }
            }
        }
    }

    private void applyOperation(ResumeContent content, ResumeEditOperationDTO operation,
                                Map<String, String> generatedIds) {
        String op = upper(operation.op());
        String target = upper(operation.target());
        switch (target) {
            case "BASIC_INFO" -> applyBasicInfo(content, operation);
            case "SKILL" -> applySkills(content, operation, op);
            case "EDUCATION" -> applyEducation(content, operation, op);
            case "PROJECT" -> applyProject(content, operation, op, generatedIds);
            case "HIGHLIGHT" -> applyHighlight(content, operation, op, generatedIds);
            case "INTERNSHIP" -> applyInternship(content, operation, op, generatedIds);
            case "INTERNSHIP_HIGHLIGHT" ->
                applyInternshipHighlight(content, operation, op, generatedIds);
            default -> throw invalid("包含不支持的操作目标");
        }
    }

    private void applyBasicInfo(ResumeContent content, ResumeEditOperationDTO operation) {
        ResumeContent.BasicInfo basic = content.getBasicInfo();
        String value = operation.value();
        switch (operation.field()) {
            case "name" -> basic.setName(value);
            case "phone" -> basic.setPhone(value);
            case "email" -> basic.setEmail(value);
            case "school" -> basic.setSchool(value);
            case "major" -> basic.setMajor(value);
            case "degree" -> basic.setDegree(value);
            case "targetPosition" -> basic.setTargetPosition(value);
            case "githubUrl" -> basic.setGithubUrl(value);
            case "blogUrl" -> basic.setBlogUrl(value);
            default -> throw invalid("包含不支持的个人信息字段");
        }
    }

    private void applySkills(ResumeContent content, ResumeEditOperationDTO operation, String op) {
        List<String> skills = content.getSkills();
        List<String> values = operation.values() == null ? List.of() : operation.values();
        switch (op) {
            case "ADD" -> {
                for (String value : values) {
                    if (!isBlank(value) && !skills.contains(value)
                        && skills.size() < ResumeDocumentLimits.MAX_SKILLS) {
                        skills.add(value);
                    }
                }
            }
            case "DELETE" -> skills.removeAll(values);
            case "REORDER" -> content.setSkills(new ArrayList<>(values));
            default -> throw invalid("技能不支持该操作");
        }
    }

    private void applyEducation(ResumeContent content, ResumeEditOperationDTO operation, String op) {
        List<ResumeContent.EducationItem> items = content.getEducation();
        switch (op) {
            case "ADD" -> {
                if (items.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                    throw invalid("教育经历数量已达上限");
                }
                ResumeContent.EducationItem item = new ResumeContent.EducationItem();
                item.setId(UUID.randomUUID().toString());
                var patch = operation.education();
                if (patch != null) {
                    item.setSchool(nonNull(patch.school()));
                    item.setMajor(nonNull(patch.major()));
                    item.setDegree(nonNull(patch.degree()));
                    item.setStartDate(nonNull(patch.startDate()));
                    item.setEndDate(nonNull(patch.endDate()));
                    item.setDescription(nonNull(patch.description()));
                }
                if (isBlank(item.getSchool()) && isBlank(item.getMajor())
                    && isBlank(item.getDescription())) {
                    throw invalid("新增教育经历内容为空");
                }
                items.add(item);
            }
            case "UPDATE" -> {
                ResumeContent.EducationItem item = findEducation(content, operation.id());
                var patch = operation.education();
                if (patch != null) {
                    setIfPresent(patch.school(), item::setSchool);
                    setIfPresent(patch.major(), item::setMajor);
                    setIfPresent(patch.degree(), item::setDegree);
                    setIfPresent(patch.startDate(), item::setStartDate);
                    setIfPresent(patch.endDate(), item::setEndDate);
                    setIfPresent(patch.description(), item::setDescription);
                }
                if (!isBlank(operation.field()) && operation.value() != null) {
                    setEducationField(item, operation.field(), operation.value());
                }
            }
            case "DELETE" -> items.removeIf(item -> item.getId().equals(operation.id()));
            default -> throw invalid("教育经历不支持该操作");
        }
    }

    private void applyProject(ResumeContent content, ResumeEditOperationDTO operation, String op,
                              Map<String, String> generatedIds) {
        List<ResumeContent.ProjectItem> items = content.getProjects();
        switch (op) {
            case "ADD" -> {
                if (items.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                    throw invalid("项目经历数量已达上限");
                }
                String newId = UUID.randomUUID().toString();
                if (!isBlank(operation.id())) {
                    generatedIds.put(operation.id(), newId);
                }
                ResumeContent.ProjectItem item = new ResumeContent.ProjectItem();
                item.setId(newId);
                var patch = operation.project();
                if (patch != null) {
                    item.setName(nonNull(patch.name()));
                    item.setUrl(nonNull(patch.url()));
                    item.setSummary(nonNull(patch.summary()));
                    item.setStartDate(nonNull(patch.startDate()));
                    item.setEndDate(nonNull(patch.endDate()));
                    if (patch.techStack() != null) {
                        item.setTechStack(new ArrayList<>(patch.techStack()));
                    }
                    if (patch.highlights() != null) {
                        for (String text : patch.highlights()) {
                            if (isBlank(text)) {
                                continue;
                            }
                            ResumeContent.HighlightItem highlight = new ResumeContent.HighlightItem();
                            highlight.setId(UUID.randomUUID().toString());
                            highlight.setContent(text);
                            item.getHighlights().add(highlight);
                        }
                    }
                }
                if (isBlank(item.getName()) && isBlank(item.getSummary())) {
                    throw invalid("新增项目内容为空");
                }
                items.add(item);
            }
            case "UPDATE" -> {
                ResumeContent.ProjectItem item = findProjectOrThrow(content, operation.id());
                var patch = operation.project();
                if (patch != null) {
                    setIfPresent(patch.name(), item::setName);
                    setIfPresent(patch.url(), item::setUrl);
                    setIfPresent(patch.summary(), item::setSummary);
                    setIfPresent(patch.startDate(), item::setStartDate);
                    setIfPresent(patch.endDate(), item::setEndDate);
                    if (patch.techStack() != null) {
                        item.setTechStack(new ArrayList<>(patch.techStack()));
                    }
                }
                if (!isBlank(operation.field())) {
                    if ("techStack".equals(operation.field()) && operation.values() != null) {
                        item.setTechStack(new ArrayList<>(operation.values()));
                    } else if (operation.value() != null) {
                        setProjectField(item, operation.field(), operation.value());
                    }
                }
            }
            case "DELETE" -> items.removeIf(item -> item.getId().equals(operation.id()));
            default -> throw invalid("项目经历不支持该操作");
        }
    }

    private void applyHighlight(ResumeContent content, ResumeEditOperationDTO operation, String op,
                                Map<String, String> generatedIds) {
        String parentId = operation.parentId();
        String resolved = parentId == null ? null
            : generatedIds.getOrDefault(parentId, parentId);
        switch (op) {
            case "ADD" -> {
                ResumeContent.ProjectItem project = findProjectOrThrow(content, resolved);
                if (project.getHighlights().size() >= ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_PROJECT) {
                    throw invalid("项目技术亮点数量已达上限");
                }
                String text = operation.highlight() != null && !isBlank(operation.highlight().content())
                    ? operation.highlight().content() : operation.value();
                if (isBlank(text)) {
                    throw invalid("新增技术亮点内容为空");
                }
                ResumeContent.HighlightItem highlight = new ResumeContent.HighlightItem();
                highlight.setId(UUID.randomUUID().toString());
                highlight.setContent(text);
                if (operation.highlight() != null && operation.highlight().sortOrder() != null) {
                    int position = Math.max(1, Math.min(operation.highlight().sortOrder(),
                        project.getHighlights().size() + 1));
                    project.getHighlights().add(position - 1, highlight);
                } else {
                    project.getHighlights().add(highlight);
                }
            }
            case "UPDATE" -> {
                ResumeContent.HighlightItem highlight = findHighlightOrThrow(content, operation.id());
                var patch = operation.highlight();
                if (patch != null) {
                    setIfPresent(patch.content(), highlight::setContent);
                }
                if ("content".equals(operation.field()) && operation.value() != null) {
                    highlight.setContent(operation.value());
                }
            }
            case "DELETE" -> {
                for (ResumeContent.ProjectItem project : content.getProjects()) {
                    project.getHighlights().removeIf(
                        highlight -> highlight.getId().equals(operation.id()));
                }
            }
            case "REORDER" -> {
                ResumeContent.ProjectItem project = findProjectOrThrow(content, resolved);
                reorderHighlights(project.getHighlights(), operation.values());
            }
            default -> throw invalid("技术亮点不支持该操作");
        }
    }

    private void applyInternship(ResumeContent content, ResumeEditOperationDTO operation, String op,
                                 Map<String, String> generatedIds) {
        List<ResumeContent.InternshipItem> items = content.getInternships();
        switch (op) {
            case "ADD" -> {
                if (items.size() >= ResumeDocumentLimits.MAX_LIST_ITEMS) {
                    throw invalid("实习经历数量已达上限");
                }
                String newId = UUID.randomUUID().toString();
                if (!isBlank(operation.id())) {
                    generatedIds.put(operation.id(), newId);
                }
                ResumeContent.InternshipItem item = new ResumeContent.InternshipItem();
                item.setId(newId);
                var patch = operation.internship();
                if (patch != null) {
                    item.setCompany(nonNull(patch.company()));
                    item.setPosition(nonNull(patch.position()));
                    item.setStartDate(nonNull(patch.startDate()));
                    item.setEndDate(nonNull(patch.endDate()));
                    item.setDescription(nonNull(patch.description()));
                    if (patch.highlights() != null) {
                        for (String text : patch.highlights()) {
                            if (isBlank(text)) {
                                continue;
                            }
                            ResumeContent.HighlightItem highlight = new ResumeContent.HighlightItem();
                            highlight.setId(UUID.randomUUID().toString());
                            highlight.setContent(text);
                            item.getHighlights().add(highlight);
                        }
                    }
                }
                if (isBlank(item.getCompany()) && isBlank(item.getPosition())
                    && isBlank(item.getDescription()) && item.getHighlights().isEmpty()) {
                    throw invalid("新增实习经历内容为空");
                }
                items.add(item);
            }
            case "UPDATE" -> {
                ResumeContent.InternshipItem item = findInternshipOrThrow(content, operation.id());
                var patch = operation.internship();
                if (patch != null) {
                    setIfPresent(patch.company(), item::setCompany);
                    setIfPresent(patch.position(), item::setPosition);
                    setIfPresent(patch.startDate(), item::setStartDate);
                    setIfPresent(patch.endDate(), item::setEndDate);
                    setIfPresent(patch.description(), item::setDescription);
                }
                if (!isBlank(operation.field()) && operation.value() != null) {
                    setInternshipField(item, operation.field(), operation.value());
                }
            }
            case "DELETE" -> items.removeIf(item -> item.getId().equals(operation.id()));
            default -> throw invalid("实习经历不支持该操作");
        }
    }

    private void applyInternshipHighlight(ResumeContent content, ResumeEditOperationDTO operation,
                                          String op, Map<String, String> generatedIds) {
        String parentId = operation.parentId();
        String resolved = parentId == null ? null
            : generatedIds.getOrDefault(parentId, parentId);
        switch (op) {
            case "ADD" -> {
                ResumeContent.InternshipItem internship = findInternshipOrThrow(content, resolved);
                if (internship.getHighlights().size()
                    >= ResumeDocumentLimits.MAX_HIGHLIGHTS_PER_INTERNSHIP) {
                    throw invalid("实习工作与成果数量已达上限");
                }
                String text = operation.highlight() != null && !isBlank(operation.highlight().content())
                    ? operation.highlight().content() : operation.value();
                if (isBlank(text)) {
                    throw invalid("新增工作与成果内容为空");
                }
                ResumeContent.HighlightItem highlight = new ResumeContent.HighlightItem();
                highlight.setId(UUID.randomUUID().toString());
                highlight.setContent(text);
                if (operation.highlight() != null && operation.highlight().sortOrder() != null) {
                    int position = Math.max(1, Math.min(operation.highlight().sortOrder(),
                        internship.getHighlights().size() + 1));
                    internship.getHighlights().add(position - 1, highlight);
                } else {
                    internship.getHighlights().add(highlight);
                }
            }
            case "UPDATE" -> {
                ResumeContent.HighlightItem highlight =
                    findInternshipHighlightOrThrow(content, operation.id());
                if (operation.highlight() != null) {
                    setIfPresent(operation.highlight().content(), highlight::setContent);
                }
                if ("content".equals(operation.field()) && operation.value() != null) {
                    highlight.setContent(operation.value());
                }
            }
            case "DELETE" -> {
                for (ResumeContent.InternshipItem internship : content.getInternships()) {
                    internship.getHighlights().removeIf(
                        highlight -> highlight.getId().equals(operation.id()));
                }
            }
            case "REORDER" -> {
                ResumeContent.InternshipItem internship = findInternshipOrThrow(content, resolved);
                reorderHighlights(internship.getHighlights(), operation.values());
            }
            default -> throw invalid("工作与成果不支持该操作");
        }
    }

    private void reorderHighlights(List<ResumeContent.HighlightItem> highlights, List<String> values) {
        Map<String, ResumeContent.HighlightItem> byId = new HashMap<>();
        for (ResumeContent.HighlightItem highlight : highlights) {
            byId.put(highlight.getId(), highlight);
        }
        List<ResumeContent.HighlightItem> reordered = new ArrayList<>();
        if (values != null) {
            for (String id : values) {
                ResumeContent.HighlightItem found = byId.remove(id);
                if (found != null) {
                    reordered.add(found);
                }
            }
        }
        for (int index = 0; index < reordered.size(); index++) {
            reordered.get(index).setSortOrder(index + 1);
        }
        highlights.clear();
        highlights.addAll(reordered);
    }

    private void setInternshipField(ResumeContent.InternshipItem item, String field, String value) {
        switch (field) {
            case "company" -> item.setCompany(value);
            case "position" -> item.setPosition(value);
            case "startDate" -> item.setStartDate(value);
            case "endDate" -> item.setEndDate(value);
            case "description" -> item.setDescription(value);
            default -> throw invalid("包含不支持的实习经历字段");
        }
    }

    private void setEducationField(ResumeContent.EducationItem item, String field, String value) {
        switch (field) {
            case "school" -> item.setSchool(value);
            case "major" -> item.setMajor(value);
            case "degree" -> item.setDegree(value);
            case "startDate" -> item.setStartDate(value);
            case "endDate" -> item.setEndDate(value);
            case "description" -> item.setDescription(value);
            default -> throw invalid("包含不支持的教育经历字段");
        }
    }

    private void setProjectField(ResumeContent.ProjectItem item, String field, String value) {
        switch (field) {
            case "name" -> item.setName(value);
            case "url" -> item.setUrl(value);
            case "summary" -> item.setSummary(value);
            case "startDate" -> item.setStartDate(value);
            case "endDate" -> item.setEndDate(value);
            default -> throw invalid("包含不支持的项目字段");
        }
    }

    private ResumeContent.EducationItem findEducation(ResumeContent content, String id) {
        return content.getEducation().stream()
            .filter(item -> item.getId().equals(id))
            .findFirst()
            .orElseThrow(() -> invalid("教育经历不存在"));
    }

    private ResumeContent.ProjectItem findProject(ResumeContent content, String id) {
        if (id == null) {
            return null;
        }
        return content.getProjects().stream()
            .filter(item -> item.getId().equals(id))
            .findFirst()
            .orElse(null);
    }

    private ResumeContent.ProjectItem findProjectOrThrow(ResumeContent content, String id) {
        ResumeContent.ProjectItem project = findProject(content, id);
        if (project == null) {
            throw invalid("所属项目不存在");
        }
        return project;
    }

    private ResumeContent.InternshipItem findInternship(ResumeContent content, String id) {
        if (id == null) {
            return null;
        }
        return content.getInternships().stream()
            .filter(item -> item.getId().equals(id))
            .findFirst()
            .orElse(null);
    }

    private ResumeContent.InternshipItem findInternshipOrThrow(ResumeContent content, String id) {
        ResumeContent.InternshipItem internship = findInternship(content, id);
        if (internship == null) {
            throw invalid("所属实习不存在");
        }
        return internship;
    }

    /** 项目与实习都能作为亮点列表的宿主，用于 REORDER 校验。 */
    private List<ResumeContent.HighlightItem> findHighlightList(ResumeContent content, String parentId) {
        ResumeContent.ProjectItem project = findProject(content, parentId);
        if (project != null) {
            return project.getHighlights();
        }
        ResumeContent.InternshipItem internship = findInternship(content, parentId);
        return internship == null ? null : internship.getHighlights();
    }

    private ResumeContent.HighlightItem findInternshipHighlightOrThrow(ResumeContent content, String id) {
        for (ResumeContent.InternshipItem internship : content.getInternships()) {
            for (ResumeContent.HighlightItem highlight : internship.getHighlights()) {
                if (highlight.getId().equals(id)) {
                    return highlight;
                }
            }
        }
        throw invalid("工作与成果不存在");
    }

    private ResumeContent.HighlightItem findHighlightOrThrow(ResumeContent content, String id) {
        for (ResumeContent.ProjectItem project : content.getProjects()) {
            for (ResumeContent.HighlightItem highlight : project.getHighlights()) {
                if (highlight.getId().equals(id)) {
                    return highlight;
                }
            }
        }
        throw invalid("技术亮点不存在");
    }

    private Set<String> collectIds(ResumeContent content) {
        Set<String> ids = new HashSet<>();
        for (ResumeContent.EducationItem item : content.getEducation()) {
            ids.add(item.getId());
        }
        for (ResumeContent.ProjectItem project : content.getProjects()) {
            ids.add(project.getId());
            for (ResumeContent.HighlightItem highlight : project.getHighlights()) {
                ids.add(highlight.getId());
            }
        }
        for (ResumeContent.InternshipItem item : content.getInternships()) {
            ids.add(item.getId());
            for (ResumeContent.HighlightItem highlight : item.getHighlights()) {
                ids.add(highlight.getId());
            }
        }
        for (ResumeContent.AwardItem item : content.getAwards()) {
            ids.add(item.getId());
        }
        return ids;
    }

    private boolean isPermutation(List<String> values, List<String> existing) {
        return new HashSet<>(values).equals(new HashSet<>(existing))
            && values.size() == existing.size();
    }

    private String highlightContent(ResumeEditOperationDTO operation) {
        return operation.highlight() == null ? null : operation.highlight().content();
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String nonNull(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void setIfPresent(String value, Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private void checkLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw invalid(label + "长度超过上限 " + max);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.RESUME_EDIT_PROPOSAL_INVALID, message);
    }
}
