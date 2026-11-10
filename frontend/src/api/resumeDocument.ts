import {request} from './request';
import {buildResumeViewModel} from '../types/resumeViewModel';
import type {
  ResumeChatTurn,
  ResumeContent,
  ResumeDocument,
  ResumeEditProposal,
} from '../types/resumeDocument';

export interface UpdateResumeDocumentPayload {
  title: string;
  templateCode: string;
  content: ResumeContent;
  version: number;
}

/** 在线简历文档接口，统一复用 request.ts 的 Cookie + CSRF 与 Result 解包。 */
export const resumeDocumentApi = {
  async exportPdf(content: ResumeContent): Promise<Blob> {
    const {header, sections} = buildResumeViewModel(content);
    return request.download('/api/resume-documents/export/pdf', {
      method: 'POST',
      data: {
        header,
        sections: sections.map(({title, skills, items}) => ({
          title, skills,
          items: items.map(({title, subtitle, date, summary, links, tags, highlights}) => ({
            title, subtitle, date, summary, links, tags, highlights,
          })),
        })),
      },
    });
  },

  async list(): Promise<ResumeDocument[]> {
    return request.get<ResumeDocument[]>('/api/resume-documents');
  },

  async createBlank(title?: string): Promise<ResumeDocument> {
    return request.post<ResumeDocument>('/api/resume-documents', {title: title ?? ''});
  },

  async initFromResume(resumeId: number, title?: string): Promise<ResumeDocument> {
    return request.post<ResumeDocument>('/api/resume-documents/from-resume', {
      resumeId,
      title: title ?? '',
    });
  },

  async get(documentId: number): Promise<ResumeDocument> {
    return request.get<ResumeDocument>(`/api/resume-documents/${documentId}`);
  },

  async update(documentId: number, payload: UpdateResumeDocumentPayload): Promise<ResumeDocument> {
    return request.put<ResumeDocument>(`/api/resume-documents/${documentId}`, payload);
  },

  async remove(documentId: number): Promise<void> {
    return request.delete<void>(`/api/resume-documents/${documentId}`);
  },

  async chat(documentId: number, message: string, history: ResumeChatTurn[]): Promise<ResumeEditProposal> {
    return request.post<ResumeEditProposal>(`/api/resume-documents/${documentId}/chat`, {
      message,
      history,
    });
  },

  async applyProposal(
    documentId: number,
    proposal: ResumeEditProposal,
    version: number,
  ): Promise<ResumeDocument> {
    return request.post<ResumeDocument>(
      `/api/resume-documents/${documentId}/proposals/apply`,
      {proposal, version},
    );
  },
};
