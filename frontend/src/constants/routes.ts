export const ROUTES = {
  interview: '/interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  resumeHistory: '/history',
  /** 简历管理模块内部的在线编辑工作台，不是新的 Dock 入口。 */
  resumeStudio: (documentId: number) => `/history/studio/${documentId}`,
  settings: '/settings',
} as const;

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
  resumeStudio: 'history/studio/:documentId',
} as const;
