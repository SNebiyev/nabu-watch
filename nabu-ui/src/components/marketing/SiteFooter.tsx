import {Shield} from "lucide-react";

export function SiteFooter() {
  return (
    <footer className="border-t border-slate-800/60 pt-10 pb-20 px-4 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-7xl flex flex-col sm:flex-row items-center justify-between gap-6">
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-sky-500/10 border border-sky-500/30">
            <Shield className="h-3.5 w-3.5 text-sky-400" />
          </div>
          <span className="text-sm font-semibold text-slate-400">
            nabu<span className="text-sky-400">.watch</span>
          </span>
          <span className="text-xs text-slate-600 ml-2 hidden sm:inline">
            — Automated compliance monitoring
          </span>
        </div>

        <p className="text-xs text-slate-600">
          &copy; {new Date().getFullYear()} Samir Nabiyev. Open source under Apache-2.0.
        </p>
      </div>
    </footer>
  );
}
