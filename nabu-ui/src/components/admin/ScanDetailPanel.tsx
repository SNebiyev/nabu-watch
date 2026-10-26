import {ExternalLink} from "lucide-react";
import {BannerDetails, ComplianceDetails} from "@/components/dashboard/drawer/ComplianceDetails";
import {SslDetails} from "@/components/dashboard/drawer/SslDetails";
import {TrackerDetails} from "@/components/dashboard/drawer/TrackerDetails";

interface ScanDetailPanelProps {
    resultJson: Record<string, unknown> | null;
    monitorType: string;
    subType: string;
}

export function ScanDetailPanel({resultJson, monitorType, subType}: ScanDetailPanelProps) {
    if (!resultJson) {
        return (
            <div
                className="flex items-center gap-2 rounded-xl border border-border bg-secondary/30 px-4 py-3 text-sm text-muted-foreground">
                No result data available for this scan.
            </div>
        );
    }

    const isSSL = monitorType === "SSL";

    return (
        <>
            {resultJson.target_url && (
                <div
                    className="mb-3 flex items-center gap-1.5 rounded-xl border border-border bg-secondary/30 px-3 py-2">
                    <ExternalLink className="h-3.5 w-3.5 shrink-0 text-muted-foreground"/>
                    <span className="truncate font-mono text-[11px] text-muted-foreground">
                        {String(resultJson.target_url)}
                    </span>
                </div>
            )}
            {isSSL
                ? <SslDetails result={resultJson}/>
                : subType === "COOKIE_BANNER"
                    ? <BannerDetails result={resultJson}/>
                    : subType === "TRACKER_DETECTION"
                        ? <TrackerDetails result={resultJson}/>
                        : <ComplianceDetails result={resultJson}
                                             subType={subType as Parameters<typeof ComplianceDetails>[0]["subType"]}/>
            }
        </>
    );
}
