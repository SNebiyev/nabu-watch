import type {Metadata} from "next";

export const metadata: Metadata = {
  title: "Sign in",
  description: "Sign in to your nabu-watch account to manage SSL and compliance monitoring for your websites.",
  alternates: {canonical: "/login"},
  openGraph: {title: "Sign in — nabu-watch", url: "/login"},
};

export default function LoginLayout({children}: {children: React.ReactNode}) {
  return children;
}
