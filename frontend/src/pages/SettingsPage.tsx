import { Settings } from 'lucide-react';
import MyAiSettings from '../components/MyAiSettings';

export default function SettingsPage() {
  return (
    <div className="mx-auto max-w-4xl">
      <header className="mb-8 flex items-center gap-4">
        <div className="rounded-xl bg-gradient-to-r from-neon-purple to-neon-blue p-3">
          <Settings className="h-6 w-6 text-white" />
        </div>
        <h1 className="text-2xl font-bold text-slate-800 dark:text-white">系统设置</h1>
      </header>
      <MyAiSettings />
    </div>
  );
}
