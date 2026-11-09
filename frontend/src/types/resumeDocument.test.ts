import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createBlankResumeContent,
  describeOperation,
  hasDeleteOperation,
  normalizeProposal,
  normalizeResumeContent,
} from './resumeDocument.ts';
import {ROUTES, ROUTE_PATTERNS} from '../constants/routes.ts';

test('空白简历包含完整结构且所有数组为空', () => {
  const content = createBlankResumeContent();
  assert.deepEqual(Object.keys(content).sort(), [
    'awards', 'basicInfo', 'education', 'internships', 'projects', 'skills',
  ]);
  assert.deepEqual(content.education, []);
  assert.deepEqual(content.projects, []);
  assert.deepEqual(content.skills, []);
  assert.deepEqual(content.internships, []);
  assert.deepEqual(content.awards, []);
  assert.equal(content.basicInfo.name, '');
});

test('规范化任意输入都产出可渲染的完整结构', () => {
  const content = normalizeResumeContent({basicInfo: {name: '张三', bad: 1}});
  assert.equal(content.basicInfo.name, '张三');
  assert.equal(content.basicInfo.phone, '');
  assert.ok(Array.isArray(content.projects));
  assert.ok(Array.isArray(content.skills));
});

test('项目与技术亮点保留稳定 ID 并按 sortOrder 排序', () => {
  const content = normalizeResumeContent({
    projects: [{
      id: 'project-1',
      name: '智能面试平台',
      highlights: [
        {id: 'h2', content: '第二条', sortOrder: 2},
        {id: 'h1', content: '第一条', sortOrder: 1},
      ],
    }],
  });
  assert.equal(content.projects[0].id, 'project-1');
  assert.deepEqual(content.projects[0].highlights.map(item => item.id), ['h1', 'h2']);
  assert.deepEqual(content.projects[0].highlights.map(item => item.sortOrder), [1, 2]);
});

test('缺失 ID 的记录会被补齐且互不重复', () => {
  const content = normalizeResumeContent({
    projects: [{name: 'A', highlights: [{content: 'x'}, {content: 'y'}]}],
  });
  assert.ok(content.projects[0].id.length > 0);
  assert.notEqual(content.projects[0].highlights[0].id, content.projects[0].highlights[1].id);
});

test('工作区路由挂在 /history 下，不新增 Dock 入口', () => {
  assert.equal(ROUTES.resumeStudio(7), '/history/studio/7');
  assert.equal(ROUTE_PATTERNS.resumeStudio, 'history/studio/:documentId');
  assert.equal(ROUTES.resumeHistory, '/history');
  assert.equal(ROUTES.settings, '/settings');
});

test('提案规范化保留确认所需字段', () => {
  const proposal = normalizeProposal({
    reply: '已生成',
    changeSummary: ['新增项目'],
    operations: [{op: 'ADD', target: 'PROJECT', project: {name: '面试平台'}}],
    needsClarification: false,
    baseVersion: 3,
  });
  assert.ok(proposal);
  assert.equal(proposal?.baseVersion, 3);
  assert.equal(proposal?.operations.length, 1);
  assert.equal(proposal?.needsClarification, false);
});

test('信息不足的追问型提案被正确识别', () => {
  const proposal = normalizeProposal({
    reply: '需要补充信息',
    needsClarification: true,
    clarificationQuestion: '请补充项目名称',
  });
  assert.equal(proposal?.needsClarification, true);
  assert.equal(proposal?.clarificationQuestion, '请补充项目名称');
  assert.deepEqual(proposal?.operations, []);
});

test('提案明细可读且能识别高影响删除操作', () => {
  const operation = {op: 'DELETE', target: 'PROJECT', value: '旧项目'};
  assert.match(describeOperation(operation), /删除项目经历/);
  const proposal = normalizeProposal({
    reply: '待确认',
    operations: [operation],
    needsClarification: false,
    baseVersion: 1,
  });
  assert.equal(hasDeleteOperation(proposal ?? null), true);
  assert.equal(hasDeleteOperation(null), false);
});

test('旧版实习 description 完整保留，新的工作与成果条目按序号排序', () => {
  const legacy = normalizeResumeContent({
    internships: [{id: 'old-1', company: '某科技', description: '参与接口开发与联调'}],
  });
  assert.equal(legacy.internships[0].description, '参与接口开发与联调');
  assert.deepEqual(legacy.internships[0].highlights, []);

  const modern = normalizeResumeContent({
    internships: [{
      id: 'new-1', company: '腾讯',
      highlights: [
        {id: 'b', content: '第二条', sortOrder: 2},
        {id: 'a', content: '第一条', sortOrder: 1},
      ],
    }],
  });
  assert.deepEqual(modern.internships[0].highlights.map(item => item.content),
    ['第一条', '第二条']);
  assert.deepEqual(modern.internships[0].highlights.map(item => item.sortOrder), [1, 2]);
});

test('实习相关 AI 操作目标在提案明细中有可读名称', () => {
  assert.match(describeOperation({op: 'UPDATE', target: 'INTERNSHIP',
    internship: {company: '腾讯'}}), /修改实习经历：腾讯/);
  assert.match(describeOperation({op: 'REORDER', target: 'INTERNSHIP_HIGHLIGHT'}),
    /调整顺序工作与成果/);
});
