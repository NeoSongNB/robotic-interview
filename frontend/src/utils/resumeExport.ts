import type { ResumeContent } from '../types/resumeDocument';

const has = (value?: string) => Boolean(value && value.trim());
const period = (start?: string, end?: string) => [start, end].filter(Boolean).join(' — ');

/**
 * 把结构化简历转成 Markdown。纯前端生成，不新增后端接口；
 * 转换函数保持纯净，便于 node --test 直接断言。
 */
export function resumeToMarkdown(title: string, content: ResumeContent): string {
  const lines: string[] = [`# ${title || '简历'}`, ''];

  const info = content.basicInfo;
  const basics: Array<[string, string]> = ([
    ['姓名', info.name],
    ['电话', info.phone],
    ['邮箱', info.email],
    ['学校', info.school],
    ['专业', info.major],
    ['学历', info.degree],
    ['求职意向', info.targetPosition],
    ['GitHub', info.githubUrl],
    ['博客', info.blogUrl],
  ] as Array<[string, string]>).filter(([, value]) => has(value));
  if (basics.length > 0) {
    lines.push('## 基本信息', '', ...basics.map(([label, value]) => `- ${label}：${value}`), '');
  }

  if (content.education.length > 0) {
    lines.push('## 教育经历', '');
    for (const item of content.education) {
      lines.push(`### ${item.school || '未命名学校'}`, '');
      const meta = [item.major, item.degree, period(item.startDate, item.endDate)]
        .filter(Boolean).join(' · ');
      if (meta) lines.push(meta, '');
      if (has(item.description)) lines.push(item.description, '');
    }
  }

  if (content.skills.length > 0) {
    lines.push('## 专业技能', '', ...content.skills.filter(has).map((skill) => `- ${skill}`), '');
  }

  if (content.projects.length > 0) {
    lines.push('## 项目经历', '');
    for (const project of content.projects) {
      lines.push(`### ${project.name || '未命名项目'}`, '');
      const meta = [period(project.startDate, project.endDate), project.url]
        .filter(Boolean).join(' · ');
      if (meta) lines.push(meta, '');
      if (project.techStack.length > 0) lines.push(`技术栈：${project.techStack.join('、')}`, '');
      if (has(project.summary)) lines.push(project.summary, '');
      const highlights = [...project.highlights]
        .sort((a, b) => a.sortOrder - b.sortOrder)
        .filter((item) => has(item.content));
      if (highlights.length > 0) {
        lines.push(...highlights.map((item) => `- ${item.content}`), '');
      }
    }
  }

  if (content.internships.length > 0) {
    lines.push('## 实习经历', '');
    for (const item of content.internships) {
      lines.push(`### ${item.company || '未命名公司'}`, '');
      const meta = [item.position, period(item.startDate, item.endDate)]
        .filter(Boolean).join(' · ');
      if (meta) lines.push(meta, '');
      if (has(item.description)) lines.push(item.description, '');
    }
  }

  if (content.awards.length > 0) {
    lines.push('## 获奖经历', '');
    for (const item of content.awards) {
      lines.push(`### ${item.name || '未命名奖项'}`, '');
      if (has(item.date)) lines.push(item.date, '');
      if (has(item.description)) lines.push(item.description, '');
    }
  }

  return lines.join('\n').trimEnd() + '\n';
}

export function resumeToJson(title: string, content: ResumeContent): string {
  return JSON.stringify({ title, content }, null, 2);
}

/** 文件名里去掉 Windows / macOS 都不允许的字符。 */
export function safeFileStem(title: string): string {
  return (title || '简历').replace(/[\\/:*?"<>|\r\n]/g, '_').trim().slice(0, 80) || '简历';
}

/** 浏览器 Blob 下载，调用方负责生成文本内容。 */
export function downloadTextFile(filename: string, text: string, mime: string) {
  const blob = new Blob([text], { type: mime });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
