import type { MetadataRoute } from "next";
import { SITE_URL } from "@/lib/siteConfig";

export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date();

  const routes: Array<{
    path: string;
    changeFrequency: MetadataRoute.Sitemap[number]["changeFrequency"];
    priority: number;
  }> = [
    { path: "/", changeFrequency: "weekly", priority: 1.0 },
    { path: "/tools", changeFrequency: "weekly", priority: 0.85 },
    { path: "/tools/ssl-check", changeFrequency: "monthly", priority: 0.8 },
    { path: "/tools/impressum-checker", changeFrequency: "monthly", priority: 0.8 },
    { path: "/tools/privacy-policy-check", changeFrequency: "monthly", priority: 0.8 },
    { path: "/tools/cookie-banner-checker", changeFrequency: "monthly", priority: 0.8 },
    { path: "/tools/gdpr-scan", changeFrequency: "monthly", priority: 0.85 },
    { path: "/login", changeFrequency: "monthly", priority: 0.4 },
    { path: "/register", changeFrequency: "monthly", priority: 0.7 },
    { path: "/forgot-password", changeFrequency: "yearly", priority: 0.2 },
  ];

  return routes.map((route) => ({
    url: `${SITE_URL}${route.path}`,
    lastModified: now,
    changeFrequency: route.changeFrequency,
    priority: route.priority,
  }));
}
