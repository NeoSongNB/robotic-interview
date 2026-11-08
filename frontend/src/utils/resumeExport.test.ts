import test from 'node:test';
import assert from 'node:assert/strict';
import { resumeToMarkdown, resumeToJson, safeFileStem } from './resumeExport.ts';
import type { ResumeContent } from '../types/resumeDocument.ts';

const blank = (): ResumeContent => ({
  basicInfo: {
    name: '', phone: '', email: '', school: '', major: '', degree: '',
    targetPosition: '', githubUrl: '', blogUrl: '',
  },
  education: [],
  projects: [],
  skills: [],
  internships: [],
  awards: [],
});

test('空简历只输出标题，不产生空小节', () => {
  const md = resumeToMarkdown('我的简历', blank());
  assert.equal(md, '# 我的简历\n');
});

test('基本信息与技能按标签输出，空字段被忽略', () => {
  const content = blank();
  content.basicInfo.name = '林思远';
  content.basicInfo.phone = '138-0000-1234';
  content.basicInfo.targetPosition = 'Java 后端';
  content.skills = ['Java', 'Redis'];
  const md = resumeToMarkdown('简历', content);
  assert.match(md, /- 姓名：林思远/);
  assert.match(md, /- 电话：138-0000-1234/);
  assert.match(md, /- 求职意向：Java 后端/);
  assert.match(md, /## 专业技能/);
  assert.match(md, /- Java/);
  assert.match(md, /- Redis/);
  assert.doesNotMatch(md, /- 邮箱：/);
});

test('项目亮点按 sortOrder 排序而不是数组顺序', () => {
  const content = blank();
  content.projects = [{
    id: 'p1', name: '面试平台', url: '', summary: '面向求职者的练习平台',
    techStack: ['Spring Boot', 'Redis'], startDate: '2022-01', endDate: '2022-05',
    highlights: [
      { id: 'h2', content: '第二条', sortOrder: 2 },
      { id: 'h1', content: '第一条', sortOrder: 1 },
    ],
  }];
  const md = resumeToMarkdown('简历', content);
  assert.ok(md.indexOf('第一条') < md.indexOf('第二条'));
  assert.match(md, /技术栈：Spring Boot、Redis/);
});

test('JSON 导出可解析且保留完整结构', () => {
  const content = blank();
  content.basicInfo.name = '张三';
  const parsed = JSON.parse(resumeToJson('张三的简历', content));
  assert.equal(parsed.title, '张三的简历');
  assert.equal(parsed.content.basicInfo.name, '张三');
  assert.deepEqual(parsed.content.projects, []);
});

test('safeFileStem 去掉文件名非法字符并限制长度', () => {
  assert.equal(safeFileStem('a/b:c*?'), 'a_b_c__');
  assert.equal(safeFileStem(''), '简历');
  assert.equal(safeFileStem('x'.repeat(200)).length, 80);
});
