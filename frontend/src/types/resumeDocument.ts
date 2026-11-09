/**
 * 在线结构化简历类型定义。与后端 modules.resumedocument.model 一一对应。
 */

export interface ResumeBasicInfo {
  name: string;
  phone: string;
  email: string;
  school: string;
  major: string;
  degree: string;
  targetPosition: string;
  githubUrl: string;
  blogUrl: string;
}

export interface ResumeEducation {
  id: string;
  school: string;
  major: string;
  degree: string;
  startDate: string;
  endDate: string;
  description: string;
}

export interface ResumeHighlight {
  id: string;
  content: string;
  sortOrder: number;
}

export interface ResumeProject {
  id: string;
  name: string;
  url: string;
  summary: string;
  techStack: string[];
  highlights: ResumeHighlight[];
  startDate: string;
  endDate: string;
}

export interface ResumeInternship {
  id: string;
  company: string;
  position: string;
  startDate: string;
  endDate: string;
  /** 旧版单段工作内容：历史数据必须完整保留、可编辑，不能静默丢弃。 */
  description: string;
  /** 新版「工作与成果」逐条记录，复用项目亮点的稳定 ID 与 sortOrder 规则。 */
  highlights: ResumeHighlight[];
}

export interface ResumeAward {
  id: string;
  name: string;
  date: string;
  description: string;
}

export interface ResumeContent {
  basicInfo: ResumeBasicInfo;
  education: ResumeEducation[];
  projects: ResumeProject[];
  skills: string[];
  internships: ResumeInternship[];
  awards: ResumeAward[];
}

export interface ResumeDocument {
  id: number;
  title: string;
  sourceResumeId: number | null;
  content: ResumeContent;
  templateCode: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export type ResumeEditOp = 'ADD' | 'UPDATE' | 'DELETE' | 'REORDER';
export type ResumeEditTarget =
  | 'BASIC_INFO' | 'EDUCATION' | 'PROJECT' | 'HIGHLIGHT' | 'SKILL'
  | 'INTERNSHIP' | 'INTERNSHIP_HIGHLIGHT';

export interface ResumeEditOperation {
  op: string;
  target: string;
  id?: string | null;
  parentId?: string | null;
  field?: string | null;
  value?: string | null;
  values?: string[] | null;
  education?: Partial<Omit<ResumeEducation, 'id'>> | null;
  project?: {
    name?: string | null;
    url?: string | null;
    summary?: string | null;
    techStack?: string[] | null;
    highlights?: string[] | null;
    startDate?: string | null;
    endDate?: string | null;
  } | null;
  highlight?: { content?: string | null; sortOrder?: number | null } | null;
  internship?: {
    company?: string | null;
    position?: string | null;
    startDate?: string | null;
    endDate?: string | null;
    description?: string | null;
    highlights?: string[] | null;
  } | null;
}

export interface ResumeEditProposal {
  reply: string;
  changeSummary: string[];
  operations: ResumeEditOperation[];
  needsClarification: boolean;
  clarificationQuestion: string | null;
  baseVersion: number;
}

export interface ResumeChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

export type SaveStatus = 'saved' | 'saving' | 'error' | 'idle';

const BASIC_INFO_FIELDS: (keyof ResumeBasicInfo)[] = [
  'name', 'phone', 'email', 'school', 'major', 'degree',
  'targetPosition', 'githubUrl', 'blogUrl',
];

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/** 项目亮点与实习「工作与成果」共用：稳定 ID、按序号排序、回填连续 sortOrder。 */
function normalizeHighlights(raw: unknown): ResumeHighlight[] {
  return asArray(raw)
    .map((item, index) => {
      const record = isRecord(item) ? item : {};
      return {
        id: asString(record.id) || newLocalId(),
        content: asString(record.content),
        sortOrder: typeof record.sortOrder === 'number' ? record.sortOrder : index + 1,
      };
    })
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((item, index) => ({...item, sortOrder: index + 1}));
}

export function newLocalId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `local-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 把后端返回（或本地编辑产生）的任意内容规范化为完整结构，保证模板渲染不报错。 */
export function normalizeResumeContent(raw: unknown): ResumeContent {
  const source = isRecord(raw) ? raw : {};
  const infoRaw = isRecord(source.basicInfo) ? source.basicInfo : {};
  const basicInfo = {} as ResumeBasicInfo;
  for (const field of BASIC_INFO_FIELDS) {
    basicInfo[field] = asString(infoRaw[field]);
  }

  const education = asArray(source.education).map((item) => {
    const record = isRecord(item) ? item : {};
    return {
      id: asString(record.id) || newLocalId(),
      school: asString(record.school),
      major: asString(record.major),
      degree: asString(record.degree),
      startDate: asString(record.startDate),
      endDate: asString(record.endDate),
      description: asString(record.description),
    };
  });

  const projects = asArray(source.projects).map((item) => {
    const record = isRecord(item) ? item : {};
    return {
      id: asString(record.id) || newLocalId(),
      name: asString(record.name),
      url: asString(record.url),
      summary: asString(record.summary),
      techStack: asStringArray(record.techStack),
      highlights: normalizeHighlights(record.highlights),
      startDate: asString(record.startDate),
      endDate: asString(record.endDate),
    };
  });

  const internships = asArray(source.internships).map((item) => {
    const record = isRecord(item) ? item : {};
    return {
      id: asString(record.id) || newLocalId(),
      company: asString(record.company),
      position: asString(record.position),
      startDate: asString(record.startDate),
      endDate: asString(record.endDate),
      description: asString(record.description),
      highlights: normalizeHighlights(record.highlights),
    };
  });

  const awards = asArray(source.awards).map((item) => {
    const record = isRecord(item) ? item : {};
    return {
      id: asString(record.id) || newLocalId(),
      name: asString(record.name),
      date: asString(record.date),
      description: asString(record.description),
    };
  });

  return {
    basicInfo,
    education,
    projects,
    skills: asStringArray(source.skills),
    internships,
    awards,
  };
}

export function createBlankResumeContent(): ResumeContent {
  return normalizeResumeContent({});
}

const TARGET_LABELS: Record<string, string> = {
  BASIC_INFO: '个人信息',
  EDUCATION: '教育经历',
  INTERNSHIP: '实习经历',
  INTERNSHIP_HIGHLIGHT: '工作与成果',
  PROJECT: '项目经历',
  HIGHLIGHT: '技术亮点',
  SKILL: '专业技能',
};

const OP_LABELS: Record<string, string> = {
  ADD: '新增',
  UPDATE: '修改',
  DELETE: '删除',
  REORDER: '调整顺序',
};

/** 提案明细的可读描述，供确认卡片展示。 */
export function describeOperation(operation: ResumeEditOperation): string {
  const op = OP_LABELS[operation.op] ?? operation.op ?? '处理';
  const target = TARGET_LABELS[operation.target] ?? operation.target ?? '内容';
  const detail = operation.project?.name
    || operation.internship?.company
    || operation.education?.school
    || operation.value
    || operation.highlight?.content
    || (operation.values?.length ? operation.values.join('、') : '');
  return detail ? `${op}${target}：${detail}` : `${op}${target}`;
}

export function hasDeleteOperation(proposal: ResumeEditProposal | null): boolean {
  return !!proposal?.operations?.some(operation => operation.op === 'DELETE');
}

export function normalizeProposal(raw: unknown): ResumeEditProposal | null {
  if (!isRecord(raw)) return null;
  return {
    reply: asString(raw.reply),
    changeSummary: asStringArray(raw.changeSummary),
    operations: asArray(raw.operations)
      .filter(isRecord)
      .map(item => item as unknown as ResumeEditOperation),
    needsClarification: raw.needsClarification === true,
    clarificationQuestion: typeof raw.clarificationQuestion === 'string'
      ? raw.clarificationQuestion : null,
    baseVersion: typeof raw.baseVersion === 'number' ? raw.baseVersion : 0,
  };
}
