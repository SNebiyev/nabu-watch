import type {Metadata, Viewport} from "next";
import {Geist, Geist_Mono} from "next/font/google";
import {AuthProvider} from "@/lib/authContext";
import {NotificationProvider} from "@/lib/NotificationContext";
import {MessageProvider} from "@/lib/MessageContext";
import {ThemeProvider} from "@/components/ThemeProvider";
import {UmamiScript} from "@/components/UmamiScript";
import {SITE_DESCRIPTION, SITE_NAME, SITE_URL, SITE_TAGLINE, SOCIAL_TWITTER} from "@/lib/siteConfig";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

const TITLE = `${SITE_NAME} — ${SITE_TAGLINE}`;

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: TITLE,
    template: `%s — ${SITE_NAME}`,
  },
  description: SITE_DESCRIPTION,
  applicationName: SITE_NAME,
  authors: [{name: SITE_NAME, url: SITE_URL}],
  keywords: [
    "SSL monitoring",
    "GDPR compliance",
    "cookie banner checker",
    "privacy policy monitor",
    "impressum monitor",
    "website security monitoring",
    "compliance scanner",
  ],
  alternates: {
    canonical: "/",
  },
  openGraph: {
    type: "website",
    siteName: SITE_NAME,
    title: TITLE,
    description: SITE_DESCRIPTION,
    url: SITE_URL,
    locale: "en_US",
  },
  twitter: {
    card: "summary_large_image",
    site: SOCIAL_TWITTER,
    creator: SOCIAL_TWITTER,
    title: TITLE,
    description: SITE_DESCRIPTION,
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-image-preview": "large",
      "max-snippet": -1,
      "max-video-preview": -1,
    },
  },
  manifest: "/site.webmanifest",
};

export const viewport: Viewport = {
  themeColor: [
    {media: "(prefers-color-scheme: light)", color: "#ffffff"},
    {media: "(prefers-color-scheme: dark)", color: "#0b0f17"},
  ],
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full bg-background text-foreground transition-colors">
        <ThemeProvider>
            <AuthProvider>
                <NotificationProvider>
                    <MessageProvider>{children}</MessageProvider>
                </NotificationProvider>
            </AuthProvider>
        </ThemeProvider>
        <UmamiScript />
      </body>
    </html>
  );
}
