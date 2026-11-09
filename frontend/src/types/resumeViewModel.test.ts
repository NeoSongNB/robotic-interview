import assert from 'node:assert/strict';
import test from 'node:test';

import {normalizeResumeContent} from './resumeDocument.ts';
import {
  buildResumeViewModel,
  isValidLink,
  SECTION_ORDER,
  type ResumeSectionKey,
} from './resumeViewModel.ts';

function sectionKeys(raw: unknown): ResumeSectionKey[] {
  const content = normalizeResumeContent(raw);
  return buildResumeViewModel(content).sections.map(section => section.key);
}

test('默认栏目顺序：教育 → 实习 → 项目 → 技能 → 获奖', () => {
  assert.deepEqual(SECTION_ORDER, [
    'education', 'internships', 'projects', 'skills', 'awards',
  ]);
});

test('场景一：有实习的新简历，实习紧接教育经历且成果按 sortOrder 输出', () => {
  const content = normalizeResumeContent({
    basicInfo: {
      name: '张三', targetPosition: '后端开发实习生',
      phone: '13800138000', email: 'a@example.com',
      school: '深圳大学', major: '计算机科学与技术', degree: '本科',
      githubUrl: 'https://github.com/zhangsan', blogUrl: 'https://...',
    },
    education: [{id: 'edu-1', school: '深圳大学', major: '计算机科学与技术',
      degree: '本科', startDate: '2022.09', endDate: '2026.06'}],
    internships: [{
      id: 'intern-1', company: '腾讯', position: '后端实习生',
      startDate: '2025.07', endDate: '2025.09',
      highlights: [
        {id: 'ih-2', content: '第二条：用单元测试验证接口边界', sortOrder: 2},
        {id: 'ih-1', content: '第一条：负责订单模块接口开发', sortOrder: 1},
      ],
    }],
    projects: [{id: 'proj-1', name: '面试平台', summary: '面向校招的练习平台',
      highlights: [{id: 'h-1', content: '实现题库检索', sortOrder: 1}]}],
    skills: ['Java', 'Spring Boot'],
    awards: [{id: 'award-1', name: 'ACM 省赛一等奖'}],
  });

  const view = buildResumeViewModel(content);
  assert.deepEqual(view.sections.map(section => section.key), SECTION_ORDER);
  assert.equal(view.sections[1].items[0].highlights[0], '第一条：负责订单模块接口开发');
  assert.equal(view.sections[1].items[0].subtitle, '后端实习生');

  // 页首只保留姓名、意向、电话、邮箱与有效作品链接
  assert.equal(view.header.name, '张三');
  assert.deepEqual(view.header.contacts, ['13800138000', 'a@example.com']);
  assert.deepEqual(view.header.links, ['https://github.com/zhangsan']);
  assert.equal(JSON.stringify(view.header).includes('深圳大学'), false);
  assert.equal(view.educationConflict, false);
});

test('场景二：无实习但有项目时隐藏实习栏目，项目紧接教育经历', () => {
  const content = normalizeResumeContent({
    basicInfo: {name: '李四', targetPosition: '嵌入式开发'},
    education: [{id: 'edu-1', school: 'XX 大学'}],
    projects: [{id: 'proj-1', name: '智能充电桩',
      highlights: [{id: 'h-1', content: '实现 ADC 采样', sortOrder: 1}]}],
    skills: ['C 语言', 'FreeRTOS'],
  });

  assert.deepEqual(sectionKeys(content), ['education', 'projects', 'skills']);
  const view = buildResumeViewModel(content);
  assert.equal(view.sections[1].key, 'projects');
  assert.match(view.advice.join(''), /暂无实习经历/);
});

test('场景三：历史简历旧 description 与分散的教育信息不丢失、不重复输出', () => {
  const legacy = {
    basicInfo: {
      name: '王五', school: 'XX 大学', major: '软件工程', degree: '本科',
      targetPosition: 'Java 后端', githubUrl: 'https://...',
    },
    education: [],
    internships: [{
      id: 'old-intern', company: '某科技公司', position: '实习生',
      description: '参与后台接口开发与联调，完成 12 个接口的测试用例验证。',
    }],
    projects: [{id: 'old-proj', name: '课程设计', summary: '课程项目'}],
  };
  const content = normalizeResumeContent(legacy);

  // 旧文档重新保存后 description 与稳定 ID 完整保留，并补齐成果列表
  assert.equal(content.internships[0].description, legacy.internships[0].description);
  assert.equal(content.internships[0].id, 'old-intern');
  assert.deepEqual(content.internships[0].highlights, []);

  const view = buildResumeViewModel(content);
  assert.equal(view.legacyEducationShown, true);
  assert.equal(view.sections[0].items.length, 1);
  assert.equal(view.sections[0].items[0].title, 'XX 大学');
  assert.equal(view.sections[0].items[0].subtitle, '软件工程 · 本科');
  assert.equal(view.sections.find(section => section.key === 'internships')?.items[0].summary,
    legacy.internships[0].description);
  assert.equal(view.header.links.length, 0, '占位链接不能进入页首');
  assert.deepEqual(sectionKeys(legacy), ['education', 'internships', 'projects']);
});

test('两处教育信息冲突时提示核对，不静默覆盖也不重复输出', () => {
  const content = normalizeResumeContent({
    basicInfo: {school: 'AA 大学', major: '通信工程', degree: '硕士'},
    education: [{id: 'edu-1', school: 'BB 大学', major: '计算机科学与技术', degree: '本科'}],
  });
  const view = buildResumeViewModel(content);
  assert.equal(view.educationConflict, true);
  assert.match(view.warnings.join(''), /不一致/);
  assert.equal(view.sections[0].items.length, 1);
  assert.equal(view.sections[0].items[0].title, 'BB 大学');
});

test('同一段贡献与整串技术栈不重复输出，奖项只展示一次', () => {
  const content = normalizeResumeContent({
    skills: ['Java', 'Spring Boot', 'PostgreSQL'],
    projects: [
      {id: 'p1', name: '项目 A', techStack: ['Java', 'Spring Boot', 'PostgreSQL'],
        highlights: [{id: 'h1', content: '负责订单模块', sortOrder: 1}]},
      {id: 'p2', name: '项目 B', techStack: ['Redis'],
        highlights: [{id: 'h2', content: '负责订单模块', sortOrder: 1}]},
    ],
    internships: [{id: 'i1', company: '公司 A', description: '负责订单模块',
      highlights: [{id: 'ih1', content: '负责订单模块', sortOrder: 1}]}],
    awards: [{id: 'a1', name: 'ACM 一等奖'}, {id: 'a2', name: 'ACM 一等奖'}],
  });
  const view = buildResumeViewModel(content);
  const projects = view.sections.find(section => section.key === 'projects');
  const internship = view.sections.find(section => section.key === 'internships');
  const awards = view.sections.find(section => section.key === 'awards');

  assert.deepEqual(projects?.items[0].tags, [], '整串技术栈与技能栏相同则不再复述');
  assert.deepEqual(projects?.items[1].tags, ['Redis'], '与具体工作相关的技术仍保留');
  assert.deepEqual(projects?.items[1].highlights, [], '跨栏目复述的同一段贡献只输出一次');
  assert.equal(internship?.items[0].summary, '', '旧描述与成果条目相同则不重复输出');
  assert.deepEqual(internship?.items[0].highlights, ['负责订单模块']);
  assert.equal(awards?.items.length, 1);
  assert.ok(view.duplicateCount >= 3);
});

test('有效作品链接与 JD 建议规则', () => {
  assert.equal(isValidLink('https://github.com/zhangsan'), true);
  assert.equal(isValidLink('https://...'), false);
  assert.equal(isValidLink('github.com/zhangsan'), false);
  assert.equal(isValidLink(''), false);

  const content = normalizeResumeContent({
    basicInfo: {targetPosition: '嵌入式实习生'},
    skills: ['FreeRTOS', 'C 语言'],
  });
  const view = buildResumeViewModel(content, '熟悉 FreeRTOS 任务调度与 C 语言指针');
  assert.match(view.advice.join(''), /嵌入式实习生/);
  assert.match(view.advice.join(''), /FreeRTOS/);
  assert.equal(view.advice.some(item => item.includes('Java 后端')), false,
    '没有 JD 时不假定所有人都投 Java 后端');
});
