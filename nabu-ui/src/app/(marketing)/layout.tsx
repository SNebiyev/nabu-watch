import {SiteHeader} from "@/components/marketing/SiteHeader";
import {SiteFooter} from "@/components/marketing/SiteFooter";

export default function MarketingLayout({children}: {children: React.ReactNode}) {
  return (
    <div className="dark min-h-screen flex flex-col bg-slate-950 text-slate-200">
      <SiteHeader />
      <div className="flex-1">{children}</div>
      <SiteFooter />
    </div>
  );
}
