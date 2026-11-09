import {useEffect, useState, type ReactNode} from 'react';
import {
  ArrowDown, ArrowUp, ChevronDown, ChevronUp, CircleAlert, Lightbulb, Plus, Trash2,
} from 'lucide-react';
import {
  newLocalId,
  type ResumeAward,
  type ResumeBasicInfo,
  type ResumeContent,
  type ResumeEducation,
  type ResumeHighlight,
  type ResumeInternship,
  type ResumeProject,
} from '../../types/resumeDocument';
import {buildResumeViewModel, SECTION_TITLES} from '../../types/resumeViewModel';

interface ResumeContentEditorProps {
  content: ResumeContent;
  /** 有待确认的 AI 提案或请求进行中时锁定编辑，避免基于旧版本继续改。 */
  disabled?: boolean;
  onChange: (next: ResumeContent) => void;
}

const BASIC_FIELDS: {key: keyof ResumeBasicInfo; label: string; placeholder: string}[] = [
  {key: 'name', label: '姓名', placeholder: '张三'},
  {key: 'targetPosition', label: '求职意向', placeholder: 'Java 后端开发实习生'},
  {key: 'phone', label: '电话', placeholder: '13800138000'},
  {key: 'email', label: '邮箱', placeholder: 'name@example.com'},
];

const SHORT_TEXT = 200;
const LONG_TEXT = 4000;
const MAX_HIGHLIGHTS = 50;

const INTERNSHIP_WRITING_HINT =
  '建议写 2–4 条，先写最相关的一项。每条交代你做了什么、如何验证。';

function Field({label, value, onChange, disabled, placeholder, textarea, maxLength}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  placeholder?: string;
  textarea?: boolean;
  maxLength?: number;
}) {
  const className = 'dark-input px-2 py-1.5 rounded-lg text-sm text-slate-700 dark:text-slate-200 disabled:opacity-60';
  return (
    <label className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
      <span>{label}</span>
      {textarea ? (
        <textarea
          value={value}
          onChange={event => onChange(event.target.value)}
          disabled={disabled}
          rows={3}
          maxLength={maxLength ?? LONG_TEXT}
          placeholder={placeholder}
          className={`${className} resize-y`}
        />
      ) : (
        <input
          value={value}
          onChange={event => onChange(event.target.value)}
          disabled={disabled}
          maxLength={maxLength ?? SHORT_TEXT}
          placeholder={placeholder}
          className={className}
        />
      )}
    </label>
  );
}

/** 逗号 / 顿号分隔的标签输入，失焦时才提交，避免边输入边被 join 重排光标。 */
function TagInput({value, disabled, placeholder, onCommit}: {
  value: string[];
  disabled?: boolean;
  placeholder?: string;
  onCommit: (next: string[]) => void;
}) {
  const joined = value.join('、');
  const [text, setText] = useState(joined);

  useEffect(() => {
    setText(joined);
  }, [joined]);

  const commit = () => {
    const next = text
      .split(/[,，、;；]/)
      .map(item => item.trim())
      .filter(item => item.length > 0)
      .slice(0, 30);
    onCommit(next);
    setText(next.join('、'));
  };

  return (
    <input
      value={text}
      onChange={event => setText(event.target.value)}
      onBlur={commit}
      disabled={disabled}
      placeholder={placeholder}
      className="dark-input px-2 py-1.5 rounded-lg text-sm text-slate-700 dark:text-slate-200 disabled:opacity-60"
    />
  );
}

function Section({title, count, open, onToggle, onAdd, children}: {
  title: string;
  count?: number;
  open: boolean;
  onToggle: () => void;
  onAdd?: () => void;
  children: ReactNode;
}) {
  return (
    <div className="rounded-xl border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.18)] overflow-hidden">
      <div className="flex items-center justify-between px-3 py-2 bg-slate-50 dark:bg-[rgb(var(--skin-surface-deep)/0.55)]">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={open}
          className="flex flex-1 items-center gap-2 text-left text-sm font-semibold text-slate-700 dark:text-slate-200"
        >
          {open ? <ChevronUp className="w-4 h-4"/> : <ChevronDown className="w-4 h-4"/>}
          {title}
          {count !== undefined && count > 0 && (
            <span className="text-xs font-normal text-slate-400 dark:text-slate-500">{count}</span>
          )}
        </button>
        {onAdd && (
          <button
            type="button"
            onClick={onAdd}
            className="flex items-center gap-1 text-xs px-2 py-1 rounded-lg text-primary-600 dark:text-neon-cyan hover:bg-primary-50 dark:hover:bg-[rgb(var(--skin-accent)/0.16)] transition-colors"
            title={`新增${title}`}
          >
            <Plus className="w-3.5 h-3.5"/>新增
          </button>
        )}
      </div>
      {open && <div className="p-3 space-y-3">{children}</div>}
    </div>
  );
}

function OptionalFields({label, children}: {label: string; children: ReactNode}) {
  return (
    <details className="rounded-lg border border-slate-200 px-3 py-2 dark:border-[rgb(var(--skin-accent)/0.14)]">
      <summary className="cursor-pointer text-xs font-medium text-slate-600 dark:text-slate-300">
        {label}
      </summary>
      <div className="mt-3 space-y-2">{children}</div>
    </details>
  );
}

function ItemCard({title, onRemove, disabled, children}: {
  title: string;
  onRemove: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="rounded-lg border border-slate-200 dark:border-[rgb(var(--skin-accent)/0.14)] p-3 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-slate-600 dark:text-slate-300 truncate">{title}</span>
        <button
          type="button"
          onClick={onRemove}
          disabled={disabled}
          className="p-1 text-slate-400 hover:text-red-500 rounded transition-colors disabled:opacity-50"
          title="删除这一条"
        >
          <Trash2 className="w-3.5 h-3.5"/>
        </button>
      </div>
      {children}
    </div>
  );
}

/** 项目亮点与实习「工作与成果」共用同一套逐条编辑与排序交互。 */
function HighlightList({items, disabled, label, addLabel, hint, placeholder, onChange}: {
  items: ResumeHighlight[];
  disabled: boolean;
  label: string;
  addLabel: string;
  hint?: string;
  placeholder?: string;
  onChange: (next: ResumeHighlight[]) => void;
}) {
  const commit = (next: ResumeHighlight[]) => {
    onChange(next.map((item, index) => ({...item, sortOrder: index + 1})));
  };

  const add = () => {
    if (items.length >= MAX_HIGHLIGHTS) return;
    commit([...items, {id: newLocalId(), content: '', sortOrder: items.length + 1}]);
  };

  const update = (id: string, value: string) => {
    commit(items.map(item => (item.id === id ? {...item, content: value} : item)));
  };

  const remove = (id: string) => {
    commit(items.filter(item => item.id !== id));
  };

  const move = (index: number, offset: number) => {
    const target = index + offset;
    if (target < 0 || target >= items.length) return;
    const next = [...items];
    const [moved] = next.splice(index, 1);
    next.splice(target, 0, moved);
    commit(next);
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between pt-1">
        <span className="text-xs font-semibold text-slate-600 dark:text-slate-300">
          {label} <span className="font-normal text-slate-400">{items.length}</span>
        </span>
        <button
          type="button"
          onClick={add}
          disabled={disabled || items.length >= MAX_HIGHLIGHTS}
          className="flex items-center gap-1 text-xs px-2 py-1 rounded-lg text-primary-600 dark:text-neon-cyan hover:bg-primary-50 dark:hover:bg-[rgb(var(--skin-accent)/0.16)] transition-colors disabled:opacity-50"
        >
          <Plus className="w-3.5 h-3.5"/>{addLabel}
        </button>
      </div>
      {hint && <p className="text-xs text-slate-400 dark:text-slate-500 whitespace-pre-wrap">{hint}</p>}
      {items.length === 0 && (
        <p className="text-xs text-slate-400 dark:text-slate-500">
          每条单独填写，预览与 PDF 会按顺序渲染为列表。
        </p>
      )}
      {items.map((item, index) => (
        <div key={item.id} className="flex items-start gap-1">
          <div className="flex flex-col gap-0.5 pt-2">
            <button
              type="button"
              onClick={() => move(index, -1)}
              disabled={disabled || index === 0}
              className="p-0.5 text-slate-300 hover:text-primary-500 disabled:opacity-30"
              title="上移"
            >
              <ArrowUp className="w-3.5 h-3.5"/>
            </button>
            <button
              type="button"
              onClick={() => move(index, 1)}
              disabled={disabled || index === items.length - 1}
              className="p-0.5 text-slate-300 hover:text-primary-500 disabled:opacity-30"
              title="下移"
            >
              <ArrowDown className="w-3.5 h-3.5"/>
            </button>
          </div>
          <textarea
            value={item.content}
            onChange={event => update(item.id, event.target.value)}
            disabled={disabled}
            rows={2}
            maxLength={LONG_TEXT}
            placeholder={placeholder || `${label} ${index + 1}`}
            className="dark-input flex-1 px-2 py-1.5 rounded-lg text-sm text-slate-700 dark:text-slate-200 resize-y disabled:opacity-60"
          />
          <button
            type="button"
            onClick={() => remove(item.id)}
            disabled={disabled}
            className="p-1.5 text-slate-400 hover:text-red-500 rounded transition-colors disabled:opacity-50"
            title="删除这一条"
          >
            <Trash2 className="w-3.5 h-3.5"/>
          </button>
        </div>
      ))}
    </div>
  );
}

/**
 * 手动内容编辑器，分区顺序与预览 / PDF 一致：
 * 基本信息 → 教育经历 → 实习经历 → 项目经历 → 专业技能 → 获奖与证书。
 * AI 对话与手动编辑共用同一份 content，任何改动都会进入自动保存链路。
 */
export default function ResumeContentEditor({content, disabled = false, onChange}: ResumeContentEditorProps) {
  const [openKey, setOpenKey] = useState<string | null>('basic');
  const [jd, setJd] = useState('');
  const view = buildResumeViewModel(content, jd);

  const toggle = (key: string) => setOpenKey(current => current === key ? null : key);

  const patchBasic = (field: keyof ResumeBasicInfo, value: string) => {
    onChange({...content, basicInfo: {...content.basicInfo, [field]: value}});
  };

  const patchEducation = (id: string, patch: Partial<ResumeEducation>) => {
    onChange({
      ...content,
      education: content.education.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addEducation = () => {
    const {school, major, degree} = content.basicInfo;
    const useSavedEducation = view.legacyEducationShown;
    const item: ResumeEducation = {
      id: newLocalId(),
      school: useSavedEducation ? school : '',
      major: useSavedEducation ? major : '',
      degree: useSavedEducation ? degree : '',
      startDate: '', endDate: '', description: '',
    };
    onChange({
      ...content,
      basicInfo: useSavedEducation ? {...content.basicInfo, school: '', major: '', degree: ''} : content.basicInfo,
      education: [...content.education, item],
    });
    setOpenKey('education');
  };

  const removeEducation = (id: string) => {
    onChange({...content, education: content.education.filter(item => item.id !== id)});
  };

  const patchProject = (id: string, patch: Partial<ResumeProject>) => {
    onChange({
      ...content,
      projects: content.projects.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addProject = () => {
    const item: ResumeProject = {
      id: newLocalId(), name: '', url: '', summary: '', techStack: [],
      highlights: [], startDate: '', endDate: '',
    };
    onChange({...content, projects: [...content.projects, item]});
    setOpenKey('projects');
  };

  const removeProject = (id: string) => {
    onChange({...content, projects: content.projects.filter(item => item.id !== id)});
  };

  const patchInternship = (id: string, patch: Partial<ResumeInternship>) => {
    onChange({
      ...content,
      internships: content.internships.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addInternship = () => {
    const item: ResumeInternship = {
      id: newLocalId(), company: '', position: '', startDate: '', endDate: '',
      description: '', highlights: [],
    };
    onChange({...content, internships: [...content.internships, item]});
    setOpenKey('internships');
  };

  const removeInternship = (id: string) => {
    onChange({...content, internships: content.internships.filter(item => item.id !== id)});
  };

  /** 用户主动点击后才把旧版单段描述转为第一条成果，避免静默改写历史数据。 */
  const convertDescription = (item: ResumeInternship) => {
    const value = item.description.trim();
    if (!value) return;
    const already = item.highlights.some(highlight => highlight.content.trim() === value);
    if (!already && item.highlights.length >= MAX_HIGHLIGHTS) return;
    patchInternship(item.id, {
      highlights: already
        ? item.highlights
        : [{id: newLocalId(), content: value, sortOrder: 1}, ...item.highlights]
            .map((highlight, index) => ({...highlight, sortOrder: index + 1})),
      description: '',
    });
  };

  const patchAward = (id: string, patch: Partial<ResumeAward>) => {
    onChange({
      ...content,
      awards: content.awards.map(item => (item.id === id ? {...item, ...patch} : item)),
    });
  };

  const addAward = () => {
    const item: ResumeAward = {id: newLocalId(), name: '', date: '', description: ''};
    onChange({...content, awards: [...content.awards, item]});
    setOpenKey('awards');
  };

  const removeAward = (id: string) => {
    onChange({...content, awards: content.awards.filter(item => item.id !== id)});
  };

  return (
    <div className="flex flex-col gap-3">
      <Section title="基本信息" open={openKey === 'basic'} onToggle={() => toggle('basic')}>
        <div className="grid grid-cols-2 gap-2">
          {BASIC_FIELDS.map(field => (
            <Field
              key={field.key}
              label={field.label}
              value={content.basicInfo[field.key]}
              onChange={value => patchBasic(field.key, value)}
              disabled={disabled}
              placeholder={field.placeholder}
            />
          ))}
        </div>
        <OptionalFields label="作品链接（选填）">
          <Field label="GitHub" value={content.basicInfo.githubUrl}
                 onChange={value => patchBasic('githubUrl', value)} disabled={disabled}/>
          <Field label="博客或个人主页" value={content.basicInfo.blogUrl}
                 onChange={value => patchBasic('blogUrl', value)} disabled={disabled}/>
        </OptionalFields>
      </Section>

      <Section
        title={SECTION_TITLES.education}
        count={content.education.length}
        open={openKey === 'education'}
        onToggle={() => toggle('education')}
        onAdd={disabled ? undefined : addEducation}
      >
        {view.legacyEducationShown ? (
          <p className="text-xs text-slate-400 dark:text-slate-500">
            已有教育资料。点击「新增」可带入学校、专业和学历，再补充日期。
          </p>
        ) : content.education.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">点击「新增」填写教育经历。</p>
        )}
        {view.educationConflict && (
          <OptionalFields label="核对已有教育资料">
            {view.warnings.map(warning => (
              <p key={warning} className="flex gap-2 text-xs text-amber-700 dark:text-amber-300">
                <CircleAlert className="h-3.5 w-3.5 shrink-0"/>
                {warning}
              </p>
            ))}
          </OptionalFields>
        )}
        {content.education.map(item => (
          <ItemCard key={item.id} title={item.school || '未命名学校'}
                    onRemove={() => removeEducation(item.id)} disabled={disabled}>
            <Field label="学校" value={item.school} onChange={v => patchEducation(item.id, {school: v})}
                   disabled={disabled} placeholder="XX 大学"/>
            <div className="grid grid-cols-2 gap-2">
              <Field label="专业" value={item.major} onChange={v => patchEducation(item.id, {major: v})}
                     disabled={disabled} placeholder="计算机科学与技术"/>
              <Field label="学历" value={item.degree} onChange={v => patchEducation(item.id, {degree: v})}
                     disabled={disabled} placeholder="本科"/>
              <Field label="开始时间" value={item.startDate} onChange={v => patchEducation(item.id, {startDate: v})}
                     disabled={disabled} placeholder="2022.09"/>
              <Field label="结束时间（毕业时间）" value={item.endDate}
                     onChange={v => patchEducation(item.id, {endDate: v})}
                     disabled={disabled} placeholder="2026.06"/>
            </div>
            <OptionalFields label="在校经历（选填）">
              <Field label="课程、成绩或其他经历" value={item.description}
                     onChange={v => patchEducation(item.id, {description: v})}
                     disabled={disabled} textarea/>
            </OptionalFields>
          </ItemCard>
        ))}
      </Section>

      <Section
        title={SECTION_TITLES.internships}
        count={content.internships.length}
        open={openKey === 'internships'}
        onToggle={() => toggle('internships')}
        onAdd={disabled ? undefined : addInternship}
      >
        {content.internships.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">
            还没有实习经历，点击右上角「新增」添加。
          </p>
        )}
        {content.internships.map(item => (
          <ItemCard key={item.id} title={item.company || '未命名公司'}
                    onRemove={() => removeInternship(item.id)} disabled={disabled}>
            <div className="grid grid-cols-2 gap-2">
              <Field label="公司" value={item.company} onChange={v => patchInternship(item.id, {company: v})}
                     disabled={disabled}/>
              <Field label="职位" value={item.position} onChange={v => patchInternship(item.id, {position: v})}
                     disabled={disabled}/>
              <Field label="开始时间" value={item.startDate}
                     onChange={v => patchInternship(item.id, {startDate: v})} disabled={disabled}/>
              <Field label="结束时间" value={item.endDate}
                     onChange={v => patchInternship(item.id, {endDate: v})} disabled={disabled}/>
            </div>

            <HighlightList
              items={item.highlights}
              disabled={disabled}
              label="工作与成果"
              addLabel="新增成果"
              hint={INTERNSHIP_WRITING_HINT}
              placeholder="负责的模块、你的做法和验证结果"
              onChange={next => patchInternship(item.id, {highlights: next})}
            />
            {item.description.trim() && (
              <OptionalFields label="已有工作描述">
                <Field label="工作描述" value={item.description}
                       onChange={v => patchInternship(item.id, {description: v})}
                       disabled={disabled} textarea/>
                <button type="button" onClick={() => convertDescription(item)}
                        disabled={disabled || item.highlights.length >= MAX_HIGHLIGHTS}
                        className="text-xs text-primary-600 dark:text-neon-cyan disabled:opacity-50">
                  转为第一条成果
                </button>
              </OptionalFields>
            )}
          </ItemCard>
        ))}
      </Section>

      <Section
        title={SECTION_TITLES.projects}
        count={content.projects.length}
        open={openKey === 'projects'}
        onToggle={() => toggle('projects')}
        onAdd={disabled ? undefined : addProject}
      >
        {content.projects.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">还没有项目经历，点击右上角「新增」添加。</p>
        )}
        {content.projects.map(project => (
          <ItemCard key={project.id} title={project.name || '未命名项目'}
                    onRemove={() => removeProject(project.id)} disabled={disabled}>
            <Field label="项目名称" value={project.name} onChange={v => patchProject(project.id, {name: v})}
                   disabled={disabled} placeholder="智能面试平台"/>
            <div className="grid grid-cols-2 gap-2">
              <Field label="开始时间" value={project.startDate}
                     onChange={v => patchProject(project.id, {startDate: v})} disabled={disabled} placeholder="2025.03"/>
              <Field label="结束时间" value={project.endDate}
                     onChange={v => patchProject(project.id, {endDate: v})} disabled={disabled} placeholder="2025.09"/>
            </div>
            <Field label="项目简介（背景与目标，一两句话）" value={project.summary}
                   onChange={v => patchProject(project.id, {summary: v})}
                   disabled={disabled} textarea placeholder="面向…场景，目标是…；具体方案写在亮点里"/>
            <HighlightList
              items={project.highlights}
              disabled={disabled}
              label="具体方案与贡献"
              addLabel="新增亮点"
              onChange={next => patchProject(project.id, {highlights: next})}
            />
            <OptionalFields label="项目链接与相关技术（选填）">
              <Field label="项目链接" value={project.url} onChange={v => patchProject(project.id, {url: v})}
                     disabled={disabled} placeholder="https://github.com/..."/>
              <div className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
                <span>相关技术（用逗号或顿号分隔）</span>
                <TagInput value={project.techStack} disabled={disabled}
                          placeholder="Spring Boot、PostgreSQL"
                          onCommit={next => patchProject(project.id, {techStack: next})}/>
              </div>
            </OptionalFields>
          </ItemCard>
        ))}
      </Section>

      <Section
        title={SECTION_TITLES.skills}
        count={content.skills.length}
        open={openKey === 'skills'}
        onToggle={() => toggle('skills')}
      >
        <div className="flex flex-col gap-1 text-xs text-slate-500 dark:text-slate-400">
          <span>按目标岗位拆成短条，每条一个方向；不要把所有语言都写成「精通」。用逗号或顿号分隔。</span>
          <TagInput value={content.skills} disabled={disabled}
                    placeholder="例如：熟悉 C 语言指针与内存管理、掌握 SPI/I2C 外设"
                    onCommit={next => onChange({...content, skills: next})}/>
        </div>
      </Section>

      <Section
        title={SECTION_TITLES.awards}
        count={content.awards.length}
        open={openKey === 'awards'}
        onToggle={() => toggle('awards')}
        onAdd={disabled ? undefined : addAward}
      >
        <p className="text-xs text-slate-400 dark:text-slate-500">
          同一奖项只在一处展示；团队奖项请写清个人角色。
        </p>
        {content.awards.length === 0 && (
          <p className="text-xs text-slate-400 dark:text-slate-500">没有获奖经历可以留空。</p>
        )}
        {content.awards.map(item => (
          <ItemCard key={item.id} title={item.name || '未命名奖项'}
                    onRemove={() => removeAward(item.id)} disabled={disabled}>
            <div className="grid grid-cols-2 gap-2">
              <Field label="奖项名称" value={item.name} onChange={v => patchAward(item.id, {name: v})}
                     disabled={disabled}/>
              <Field label="获奖时间" value={item.date} onChange={v => patchAward(item.id, {date: v})}
                     disabled={disabled}/>
            </div>
            <Field label="说明" value={item.description}
                   onChange={v => patchAward(item.id, {description: v})}
                   disabled={disabled} textarea/>
          </ItemCard>
        ))}
      </Section>
      <OptionalFields label="岗位匹配建议（选填）">
        <Field label="岗位描述" value={jd} onChange={setJd} textarea
               placeholder="粘贴目标岗位要求，仅用于下方建议"/>
        <ul className="space-y-2 text-xs text-slate-500 dark:text-slate-400">
          {view.advice.map(item => (
            <li key={item} className="flex gap-2">
              <Lightbulb className="h-3.5 w-3.5 shrink-0"/>{item}
            </li>
          ))}
        </ul>
      </OptionalFields>
    </div>
  );
}
