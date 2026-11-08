import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { ErrorBoundary } from './components/ErrorBoundary'
import { ToastViewport } from './components/ui/Toast'
import { SKIN_STORAGE_KEY } from './constants/themeSkin'
import { normalizeSkinId } from './utils/themeSkin'
import './index.css'

// 初始化深色模式（避免页面闪烁）
(function initTheme() {
    const stored = localStorage.getItem('theme');
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const isDark = stored === 'dark' || (!stored && prefersDark);
    if (isDark) {
        document.documentElement.classList.add('dark');
    }
})();

// 初始化页面风格：与深色模式一同在首帧前落到 <html>，避免闪回默认配色
(function initSkin() {
    let stored: string | null = null;
    try {
        stored = localStorage.getItem(SKIN_STORAGE_KEY);
    } catch {
        stored = null;
    }
    document.documentElement.dataset.skin = normalizeSkinId(stored);
})();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
    <ToastViewport />
  </React.StrictMode>,
)
