import type {LatestScanItem, ScanBatch, WebsiteHealth} from "./types";

interface Entry {
    website?: WebsiteHealth;
    scans?: LatestScanItem[];
    history?: ScanBatch[];
    cooldown?: { batch: number; domain: number };
}

const store = new Map<string, Entry>();

export const websiteCache = {
    get(id: string | undefined): Entry | undefined {
        if (!id) return undefined;
        return store.get(id);
    },
    patch(id: string | undefined, partial: Partial<Entry>): void {
        if (!id) return;
        store.set(id, {...store.get(id), ...partial});
    },
    invalidate(id: string | undefined): void {
        if (!id) return;
        store.delete(id);
    },
    clear(): void {
        store.clear();
    },
};
