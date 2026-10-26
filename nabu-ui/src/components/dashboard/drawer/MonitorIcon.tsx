import {Cookie, FileText, Globe, Lock, Radar, ScrollText, Shield, ShieldCheck, UserCheck} from "lucide-react";
import type {MonitorSubType} from "@/lib/types";

export function MonitorIcon({
                                subType,
                                className,
                            }: {
    subType: MonitorSubType;
    className?: string;
}) {
    const base = `shrink-0 ${className ?? "h-4 w-4 md:h-5 md:w-5"}`;
    switch (subType) {
        case "SSL":
            return <Lock className={`${base} text-blue-500 dark:text-blue-400`}/>;
        case "PRIVACY_POLICY":
            return <Shield className={`${base} text-violet-600 dark:text-violet-400`}/>;
        case "TERMS_OF_SERVICE":
            return <ScrollText className={`${base} text-violet-600 dark:text-violet-400`}/>;
        case "IMPRESSUM":
            return <FileText className={`${base} text-violet-600 dark:text-violet-400`}/>;
        case "COOKIE_POLICY":
            return <Cookie className={`${base} text-violet-600 dark:text-violet-400`}/>;
        case "GDPR_REQUEST":
            return <UserCheck className={`${base} text-violet-600 dark:text-violet-400`}/>;
        case "COOKIE_BANNER":
            return <ShieldCheck className={`${base} text-violet-600 dark:text-violet-400`}/>;
        case "TRACKER_DETECTION":
            return <Radar className={`${base} text-orange-600 dark:text-orange-400`}/>;
        default:
            return <Globe className={`${base} text-muted-foreground`}/>;
    }
}
