/**
 * 图表与代码块颜色的唯一出口。
 *
 * recharts / 代码高亮必须接收字符串色值，没法用 Tailwind 类，所以把这些
 * 原先散落在组件里的 hex 集中到这里；取值与原内联常量完全一致，保证视觉零回归。
 * 需要随皮肤变化的场景请改用 index.css 的 --skin-* 变量。
 */

export const RADAR_COLORS = {
  light: {
    grid: '#e2e8f0',
    tick: '#64748b',
    tooltipBg: '#fff',
    tooltipBorder: '#e2e8f0',
  },
  dark: {
    grid: '#334155',
    tick: '#94a3b8',
    tooltipBg: '#1e293b',
    tooltipBorder: '#334155',
  },
} as const;

export const PANEL_CHART_COLORS = {
  grid: '#e2e8f0',
  tick: '#94a3b8',
  tooltipBg: '#fff',
  tooltipBorder: '#e2e8f0',
} as const;
