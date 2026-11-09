/**
 * 简历展示视图模型。
 *
 * <p>编辑器提示、屏幕预览与 PDF 打印共用本文件的规则：栏目顺序、空栏目隐藏、
 * 旧版 basicInfo 教育信息兼容、重复内容抑制。模板只负责把结果渲染成 HTML，
 * 不再自行决定顺序或取舍，避免预览与打印不一致。</p>
 */
import type {ResumeContent, ResumeHighlight} from './resumeDocument';

export type ResumeSectionKey = 'education' | 'internships' | 'projects' | 'skills' | 'awards';

/** 默认顺序：教育经历保持紧凑，让第一段相关实习尽早进入视线。 */
export const SECTION_ORDER: ResumeSectionKey[] = [
  'education', 'internships', 'projects', 'skills', 'awards',
];

export const SECTION_TITLES: Record<ResumeSectionKey, string> = {
  education: '教育经历',
  internships: '实习经历',
  projects: '项目经历',
  skills: '专业技能',
  awards: '获奖与证书',
};

export interface ResumeViewItem {
  id: string;
  title: string;
  subtitle: string;
  date: string;
  /** 项目简介 / 旧版实习工作内容 / 在校经历 / 奖项说明。 */
  summary: string;
  links: string[];
  tags: string[];
  highlights: string[];
  /** 仅用于编辑器提示：该条目来自旧版基本信息的兼容展示。 */
  legacyNote: string;
}

export interface ResumeViewSection {
  key: ResumeSectionKey;
  title: string;
  items: ResumeViewItem[];
  skills: string[];
}

export interface ResumeViewModel {
  header: {
    name: string;
    targetPosition: string;
    contacts: string[];
    links: string[];
  };
  /** 已按顺序排列，空栏目不会出现（预览与 PDF 都会隐藏）。 */
  sections: ResumeViewSection[];
  /** 需要用户核对的冲突，编辑器展示，绝不静默覆盖。 */
  warnings: string[];
  /** 按求职意向与可选 JD 给出的内容取舍建议。 */
  advice: string[];
  duplicateCount: number;
  estimatedLength: number;
  legacyEducationShown: boolean;
  educationConflict: boolean;
}

function text(value?: string | null): string {
  return typeof value === 'string' ? value.trim() : '';
}

function hasText(value?: string | null): boolean {
  return text(value).length > 0;
}

function dateRange(start: string, end: string): string {
  return [start, end].filter(hasText).join(' — ');
}

/** 只保留可点击、有真实域名的作品链接；过滤「https://...」这类占位值。 */
export function isValidLink(raw?: string | null): boolean {
  const value = text(raw);
  const match = /^https?:\/\/([^/\s]+)(\/\S*)?$/i.exec(value);
  if (!match) return false;
  const host = match[1];
  return host.includes('.') && !/\.{2,}/.test(host) && !host.endsWith('.');
}

/** 去空白、去重，保持原有顺序。 */
function uniqueTexts(values: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const value of values) {
    const trimmed = text(value);
    if (trimmed && !seen.has(trimmed.toLowerCase())) {
      seen.add(trimmed.toLowerCase());
      result.push(trimmed);
    }
  }
  return result;
}

/** 按 sortOrder 排序，去掉空条目与跨栏目复述的同一段贡献。 */
function highlightTexts(highlights: ResumeHighlight[], seen: Set<string>): string[] {
  const result: string[] = [];
  for (const highlight of [...highlights].sort((a, b) => a.sortOrder - b.sortOrder)) {
    const value = text(highlight.content);
    if (!value) continue;
    const key = value.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(value);
  }
  return result;
}

function emptyItem(id: string, title: string): ResumeViewItem {
  return {
    id, title, subtitle: '', date: '', summary: '',
    links: [], tags: [], highlights: [], legacyNote: '',
  };
}

function estimate(view: {
  header: ResumeViewModel['header'];
  sections: ResumeViewSection[];
}): number {
  let total = view.header.name.length + view.header.targetPosition.length
    + view.header.contacts.join('').length + view.header.links.join('').length;
  for (const section of view.sections) {
    total += section.skills.join('').length;
    for (const item of section.items) {
      total += item.title.length + item.subtitle.length + item.date.length
        + item.summary.length + item.tags.join('').length
        + item.links.join('').length + item.highlights.join('').length
        + item.highlights.length * 12;
    }
  }
  return total;
}

/**
 * 构建展示视图。
 *
 * @param content 已规范化的简历数据
 * @param jd 可选岗位描述，仅用于生成取舍建议，不写入简历数据
 */
export function buildResumeViewModel(content: ResumeContent, jd = ''): ResumeViewModel {
  const basic = content.basicInfo;
  const seenContributions = new Set<string>();
  let duplicateCount = 0;
  const warnings: string[] = [];

  const header = {
    name: text(basic.name),
    targetPosition: text(basic.targetPosition),
    contacts: [text(basic.phone), text(basic.email)].filter(hasText),
    links: [basic.githubUrl, basic.blogUrl].filter(isValidLink).map(text),
  };

  // 教育经历：学校、专业、学历、起止时间统一在此展示。
  const education: ResumeViewItem[] = content.education
    .filter(item => hasText(item.school) || hasText(item.major) || hasText(item.degree) || hasText(item.description))
    .map(item => ({
      ...emptyItem(item.id, text(item.school) || '学校未填写'),
      subtitle: [text(item.major), text(item.degree)].filter(hasText).join(' · '),
      date: dateRange(text(item.startDate), text(item.endDate)),
      summary: text(item.description),
    }));

  // 旧简历只填了 basicInfo 的教育字段时，补一条可见的兼容展示；
  // 已存在教育记录时不再追加，避免同一份教育信息重复输出。
  const legacyFields = [text(basic.school), text(basic.major), text(basic.degree)].filter(hasText);
  let legacyEducationShown = false;
  let educationConflict = false;
  if (legacyFields.length > 0 && education.length === 0) {
    education.push({
      ...emptyItem('legacy-basic-education', text(basic.school) || '学校未填写'),
      subtitle: [text(basic.major), text(basic.degree)].filter(hasText).join(' · '),
      legacyNote: '来自旧版基本信息，建议在教育经历中补全后清理',
    });
    legacyEducationShown = true;
  } else if (legacyFields.length > 0) {
    const school = text(basic.school);
    const matched = school
      ? education.filter(item => item.title === school)
      : education;
    const conflictParts: string[] = [];
    if (school && matched.length === 0) {
      conflictParts.push(`学校「${school}」`);
    }
    if (text(basic.major) && matched.length > 0
      && matched.every(item => !item.subtitle.includes(text(basic.major)))) {
      conflictParts.push(`专业「${text(basic.major)}」`);
    }
    if (text(basic.degree) && matched.length > 0
      && matched.every(item => !item.subtitle.includes(text(basic.degree)))) {
      conflictParts.push(`学历「${text(basic.degree)}」`);
    }
    if (conflictParts.length > 0) {
      educationConflict = true;
      warnings.push(
        `基本信息的${conflictParts.join('、')}与教育经历不一致，请核对后统一；两处内容都会保留，不会自动覆盖。`,
      );
    }
  }

  // 实习经历：优先展示逐条「工作与成果」，旧版 description 完整保留但不重复输出。
  const internships: ResumeViewItem[] = content.internships
    .filter(item => hasText(item.company) || hasText(item.position)
      || hasText(item.description) || item.highlights.some(h => hasText(h.content)))
    .map(item => {
      const highlights = highlightTexts(item.highlights, seenContributions);
      const description = text(item.description);
      const duplicated = description.length > 0
        && highlights.some(value => value.toLowerCase() === description.toLowerCase());
      if (duplicated) duplicateCount += 1;
      return {
        ...emptyItem(item.id, text(item.company) || '公司未填写'),
        subtitle: text(item.position),
        date: dateRange(text(item.startDate), text(item.endDate)),
        summary: duplicated ? '' : description,
        highlights,
      };
    });

  const skills = uniqueTexts(content.skills);
  const skillKeys = new Set(skills.map(value => value.toLowerCase()));

  const projects: ResumeViewItem[] = content.projects
    .filter(item => hasText(item.name) || hasText(item.summary)
      || item.highlights.some(h => hasText(h.content)))
    .map(item => {
      const techStack = uniqueTexts(item.techStack);
      // 整串技术栈与技能栏完全相同时不再复述；具体工作相关的技术仍然保留。
      const wholeStackDuplicated = techStack.length >= 3
        && techStack.length === skills.length
        && techStack.every(value => skillKeys.has(value.toLowerCase()));
      if (wholeStackDuplicated) duplicateCount += 1;
      return {
        ...emptyItem(item.id, text(item.name) || '未命名项目'),
        date: dateRange(text(item.startDate), text(item.endDate)),
        summary: text(item.summary),
        links: isValidLink(item.url) ? [text(item.url)] : [],
        tags: wholeStackDuplicated ? [] : techStack,
        highlights: highlightTexts(item.highlights, seenContributions),
      };
    });

  // 奖项只在一处展示，同名不重复。
  const seenAwards = new Set<string>();
  const awards: ResumeViewItem[] = [];
  for (const item of content.awards) {
    const name = text(item.name);
    if (!name && !hasText(item.description)) continue;
    const key = (name || text(item.description)).toLowerCase();
    if (seenAwards.has(key)) {
      duplicateCount += 1;
      continue;
    }
    seenAwards.add(key);
    const description = text(item.description);
    awards.push({
      ...emptyItem(item.id, name || '奖项未填写'),
      date: text(item.date),
      summary: description.toLowerCase() === key ? '' : description,
    });
  }

  const itemsByKey: Record<ResumeSectionKey, ResumeViewItem[]> = {
    education, internships, projects, skills: [], awards,
  };
  const sections: ResumeViewSection[] = SECTION_ORDER
    .map(key => ({
      key,
      title: SECTION_TITLES[key],
      items: itemsByKey[key],
      skills: key === 'skills' ? skills : [],
    }))
    .filter(section => section.items.length > 0 || section.skills.length > 0);

  const estimatedLength = estimate({header, sections});
  const advice = buildAdvice(content, {
    sections, duplicateCount, estimatedLength,
  }, jd);

  return {
    header, sections, warnings, advice, duplicateCount, estimatedLength,
    legacyEducationShown, educationConflict,
  };
}

/** 纯规则建议：不调用 LLM，不假定所有人都投 Java 后端。 */
function buildAdvice(
  content: ResumeContent,
  view: {sections: ResumeViewSection[]; duplicateCount: number; estimatedLength: number},
  jd: string,
): string[] {
  const advice: string[] = [];
  const target = text(content.basicInfo.targetPosition);
  const internshipSection = view.sections.find(section => section.key === 'internships');
  const skills = content.skills.map(text).filter(hasText);

  if (!target) {
    advice.push('补充求职意向后再判断内容取舍；没有填写时按现有经历的中性顺序展示。');
  } else {
    advice.push(`以「${target}」为目标：第一段实习的第 1 条建议写最相关、最能体现个人贡献的事项。`);
  }

  if (internshipSection) {
    const thin = internshipSection.items.filter(item => item.highlights.length < 2).length;
    if (thin > 0) {
      advice.push(`每段实习建议 2–4 条工作与成果；当前有 ${thin} 段不足 2 条，可补充「做了什么 / 为什么 / 怎么验证」。`);
    }
  } else {
    advice.push('暂无实习经历，项目经历已紧接教育经历；有实习后可在编辑器「实习经历」中新增。');
  }

  advice.push('技能栏按目标岗位拆成短条，每条一个方向；项目与实习里只保留与具体工作有关的技术。');
  advice.push('没有量化数据时写清测试条件、验证方法或交付结果，不要写未经验证的百分比或业务规模。');

  const jdText = text(jd);
  if (jdText) {
    const matched = skills.filter(skill => jdText.toLowerCase().includes(skill.toLowerCase()));
    if (matched.length > 0) {
      advice.push(`岗位描述中提到的能力：${matched.slice(0, 6).join('、')}，可考虑在技能栏靠前展示。`);
    } else {
      advice.push('岗位描述与现有技能没有明显重合，可核对技能栏是否按该岗位重新组织。');
    }
  }

  if (view.duplicateCount > 0) {
    advice.push(`检测到 ${view.duplicateCount} 处重复内容，建议优先精简重复项，而不是压缩字号。`);
  }
  if (view.estimatedLength > 2200) {
    advice.push('内容较多，可能超过一页 A4；建议优先精简重复内容，允许合理分页，正文不会被裁剪、字号不会被压小。');
  }
  return advice;
}
