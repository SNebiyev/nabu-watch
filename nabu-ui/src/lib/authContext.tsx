"use client";

import {createContext, type ReactNode, useCallback, useContext, useEffect, useState,} from "react";
import {usePathname, useRouter} from "next/navigation";
import {fetchMe, getTokenPayload, type OrgInfo, switchOrganization} from "./authService";

interface AuthState {
  email: string | null;
    fullName: string | null;
  role: "USER" | "ADMIN" | null;
  isAdmin: boolean;
    /** True until the user adds their first website. null = not yet loaded from /me. */
    firstLogin: boolean | null;
    /** True once the user has verified their email address. null = not yet loaded. */
    emailVerified: boolean | null;
    /** Current active organization ID. null = not loaded. */
    currentOrganizationId: string | null;
    /** Current active organization name. null = not loaded. */
    currentOrganizationName: string | null;
    /** Caller's role in the current organization. null = not loaded. */
    currentOrganizationRole: "OWNER" | "MEMBER" | null;
    /** All organizations the user belongs to. */
    organizations: OrgInfo[];
    /** Recomputed user-level trust badge. null = not loaded. */
    verificationStatus: "UNVERIFIED" | "VERIFIED" | "VERIFIED_PENDING" | null;
    /** Switch to a different organization. Re-issues JWT and reloads state. */
    switchOrg: (orgId: string) => Promise<void>;
    /** Re-fetch /me to refresh org data (e.g. after renaming). */
    refreshMe: () => Promise<void>;
  /** true during the initial client-side hydration tick */
  loading: boolean;
}

const INITIAL: AuthState = {
    email: null,
    fullName: null,
    role: null,
    isAdmin: false,
    firstLogin: null,
    emailVerified: null,
    currentOrganizationId: null,
    currentOrganizationName: null,
    currentOrganizationRole: null,
    organizations: [],
    verificationStatus: null,
    switchOrg: async () => {
    },
    refreshMe: async () => {
    },
    loading: true,
};

const AuthContext = createContext<AuthState>(INITIAL);

/**
 * Wrap the app once at the root layout.
 *
 * On every client-side navigation the effect:
 *  1. Reads basic claims (email, role) synchronously from the JWT cookie.
 *  2. Fetches GET /api/v1/auth/me to enrich the state with `firstLogin`,
 *     `fullName`, and org data.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(INITIAL);
  const pathname = usePathname();
    const router = useRouter();

    const refreshMe = useCallback(async () => {
        try {
            const me = await fetchMe();
            const currentOrg = me.organizations.find(o => o.id === me.currentOrganizationId);
            setState(prev => ({
                ...prev,
                emailVerified: me.emailVerified,
                currentOrganizationId: me.currentOrganizationId,
                currentOrganizationName: currentOrg?.name ?? null,
                currentOrganizationRole: currentOrg?.role ?? null,
                organizations: me.organizations,
                verificationStatus: me.verificationStatus ?? null,
            }));
        } catch (err) {
            console.error("[authContext] Failed to refresh /me:", err);
        }
    }, []);

    const switchOrg = useCallback(async (orgId: string) => {
        try {
            await switchOrganization(orgId);
            // Re-fetch /me to get updated org context
            const me = await fetchMe();
            const currentOrg = me.organizations.find(o => o.id === me.currentOrganizationId);
            setState(prev => ({
                ...prev,
                currentOrganizationId: me.currentOrganizationId,
                currentOrganizationName: currentOrg?.name ?? null,
                currentOrganizationRole: currentOrg?.role ?? null,
                organizations: me.organizations,
                verificationStatus: me.verificationStatus ?? null,
            }));
            // Refresh the current page to reload data for the new org
            router.refresh();
        } catch (err) {
            console.error("[authContext] Failed to switch org:", err);
        }
    }, [router]);

  useEffect(() => {
    const payload = getTokenPayload();

      if (!payload) {
          setState({...INITIAL, switchOrg, refreshMe, loading: false});
          return;
      }

      // Fast sync path — paint the UI immediately with JWT claims
    setState({
        email: payload.sub,
        fullName: null,
        role: payload.role,
        isAdmin: payload.role === "ADMIN",
        firstLogin: null,
        emailVerified: null,
        currentOrganizationId: null,
        currentOrganizationName: null,
        currentOrganizationRole: null,
        organizations: [],
        verificationStatus: null,
        switchOrg,
        refreshMe,
        loading: true,
    });

      // Enrich with server-side data from /me
      fetchMe()
          .then((me) => {
              const currentOrg = me.organizations.find(o => o.id === me.currentOrganizationId);
              setState({
                  email: me.email,
                  fullName: me.fullName,
                  role: me.role,
                  isAdmin: me.role === "ADMIN",
                  firstLogin: me.firstLogin,
                  emailVerified: me.emailVerified,
                  currentOrganizationId: me.currentOrganizationId,
                  currentOrganizationName: currentOrg?.name ?? null,
                  currentOrganizationRole: currentOrg?.role ?? null,
                  organizations: me.organizations,
                  verificationStatus: me.verificationStatus ?? null,
                  switchOrg,
                  refreshMe,
                  loading: false,
              });
          })
          .catch(() => {
              // Network failure or expired token — keep basic JWT state
              setState((prev) => ({...prev, loading: false}));
          });
  }, [pathname, switchOrg, refreshMe]);

  return <AuthContext.Provider value={state}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  return useContext(AuthContext);
}
