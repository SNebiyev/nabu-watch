"use client";

import * as Sentry from "@sentry/nextjs";
import {useEffect} from "react";
import {buttonVariants} from "@/components/ui/button";
import "./globals.css";

export default function GlobalError({error}: {error: Error & {digest?: string}}) {
  useEffect(() => {
    Sentry.captureException(error);
  }, [error]);

  return (
    <html lang="en">
      <body className="min-h-screen flex items-center justify-center bg-background text-foreground p-8">
        <div className="max-w-lg text-center">
          <h1 className="text-2xl font-semibold mb-3">Something went wrong</h1>
          <p className="text-muted-foreground mb-6">
            The error has been reported. Please refresh the page or try again in a moment.
          </p>
          <a href="/" className={buttonVariants()}>Return home</a>
        </div>
      </body>
    </html>
  );
}
