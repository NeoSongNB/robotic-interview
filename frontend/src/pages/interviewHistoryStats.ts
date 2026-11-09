export interface InterviewStatsItem {
  status: string;
  evaluateStatus?: string | null;
  overallScore: number | null;
}

export interface InterviewStats {
  totalCount: number;
  completedCount: number;
  averageScore: number;
}

export function isCompletedStatus(status: string): boolean {
  return status === 'COMPLETED' || status === 'EVALUATED';
}

export function isEvaluateCompleted(item: InterviewStatsItem): boolean {
  return item.evaluateStatus === 'COMPLETED' || item.status === 'EVALUATED';
}

export function calculateInterviewStats<T extends InterviewStatsItem>(
  allItems: readonly T[],
): InterviewStats {
  const evaluated = allItems.filter(isEvaluateCompleted);
  const totalScore = evaluated.reduce((sum, item) => sum + (item.overallScore ?? 0), 0);

  return {
    totalCount: allItems.length,
    completedCount: evaluated.length,
    averageScore: evaluated.length > 0 ? Math.round(totalScore / evaluated.length) : 0,
  };
}
