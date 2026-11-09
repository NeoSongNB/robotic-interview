import assert from 'node:assert/strict';
import test from 'node:test';

import { calculateInterviewStats } from './interviewHistoryStats.ts';

const allItems = [
  { status: 'IN_PROGRESS', evaluateStatus: null, overallScore: null },
  { status: 'COMPLETED', evaluateStatus: 'PROCESSING', overallScore: null },
  { status: 'EVALUATED', evaluateStatus: 'COMPLETED', overallScore: 80 },
];

test('按全部记录统计，评估中的记录不计为已完成', () => {
  const stats = calculateInterviewStats(allItems);

  assert.deepEqual(stats, {
    totalCount: 3,
    completedCount: 1,
    averageScore: 80,
  });
});
