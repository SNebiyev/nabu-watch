import type {Metadata} from "next";

export const metadata: Metadata = {
  title: "Create account",
  description: "Create your nabu-watch account — start monitoring SSL, GDPR cookie banners, and security headers in minutes.",
  alternates: {canonical: "/register"},
  openGraph: {title: "Create account — nabu-watch", url: "/register"},
};

export default function RegisterLayout({children}: {children: React.ReactNode}) {
  return children;
}
