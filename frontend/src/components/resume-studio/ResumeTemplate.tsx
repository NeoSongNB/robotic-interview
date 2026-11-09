import type {ResumeContent} from '../../types/resumeDocument';
import {
  buildResumeViewModel,
  type ResumeViewItem,
  type ResumeViewSection,
} from '../../types/resumeViewModel';

interface ResumeTemplateProps {
  content: ResumeContent;
  /** true 时用于打印副本；栏目顺序与隐藏规则由视图模型统一决定。 */
  printMode?: boolean;
}

function dateRange(date: string): string {
  return date;
}

function Link({value}: {value: string}) {
  return (
    <a className="rt-link" href={value} target="_blank" rel="noreferrer">
      {value}
    </a>
  );
}

function Item({item}: {item: ResumeViewItem}) {
  return (
    <div className="rt-item">
      <div className="rt-item-head">
        <div className="rt-item-heading">
          <span className="rt-item-title">{item.title}</span>
          {item.subtitle && <span className="rt-item-sub">{`  ·  ${item.subtitle}`}</span>}
        </div>
        {item.date && <span className="rt-item-date">{dateRange(item.date)}</span>}
      </div>
      {item.links.length > 0 && (
        <div className="rt-muted-line">
          {item.links.map(link => <Link key={link} value={link}/>)}
        </div>
      )}
      {item.tags.length > 0 && (
        <div className="rt-tags">
          {item.tags.map(tag => <span className="rt-tag" key={tag}>{tag}</span>)}
        </div>
      )}
      {item.summary && <div className="rt-summary">{item.summary}</div>}
      {item.highlights.length > 0 && (
        <ul className="rt-highlights">
          {item.highlights.map((highlight, index) => (
            <li key={`${item.id}-${index}`}>{highlight}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function Section({section}: {section: ResumeViewSection}) {
  return (
    <section className="rt-section" data-section={section.key}>
      <h2 className="rt-section-title">{section.title}</h2>
      {section.skills.length > 0 && (
        <div className="rt-skills">
          {section.skills.map(skill => (
            <span className="rt-skill" key={skill}>{skill}</span>
          ))}
        </div>
      )}
      {section.items.map(item => <Item key={item.id} item={item}/>)}
    </section>
  );
}

/**
 * 默认简历模板。
 * 屏幕预览与浏览器「另存为 PDF」共用本组件、视图模型与 resume-print.css，
 * 因此栏目顺序、空栏目隐藏与内容去重在两处完全一致。
 */
export default function ResumeTemplate({content, printMode = false}: ResumeTemplateProps) {
  const view = buildResumeViewModel(content);

  return (
    <div className={`resume-template${printMode ? ' resume-template-print' : ''}`}>
      <div className="resume-sheet">
        <header className="rt-header">
          <h1 className={`rt-name${view.header.name ? '' : ' rt-name-placeholder'}`}>
            {view.header.name || '你的姓名'}
          </h1>
          <div className="rt-header-details">
            {view.header.targetPosition && (
              <div className="rt-target">求职意向：{view.header.targetPosition}</div>
            )}
            {(view.header.contacts.length > 0 || view.header.links.length > 0) && (
              <div className="rt-contact">
                {view.header.contacts.map((contact, index) => (
                  <span className="rt-contact-item" key={`${contact}-${index}`}>{contact}</span>
                ))}
                {view.header.links.map(link => (
                  <span className="rt-contact-item rt-contact-link" key={link}><Link value={link}/></span>
                ))}
              </div>
            )}
          </div>
        </header>

        {view.sections.map(section => <Section key={section.key} section={section}/>)}
      </div>
    </div>
  );
}
