"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, tokens } from "@/lib/api";
import { ErrorNotice } from "@/components/ui";

/**
 * Login, plus first-run organization bootstrap.
 *
 * The two modes share a form because a brand-new install has no account to log
 * into, and sending someone to a separate signup URL they have to discover is a
 * worse first five minutes.
 */
export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [orgName, setOrgName] = useState("");
  const [orgSlug, setOrgSlug] = useState("");
  const [fullName, setFullName] = useState("");

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result =
        mode === "login"
          ? await api.login(email, password)
          : await api.registerOrganization({
              organizationName: orgName,
              organizationSlug: orgSlug,
              adminEmail: email,
              adminFullName: fullName,
              adminPassword: password,
            });

      tokens.set(result.accessToken, result.refreshToken, result.user);
      router.replace("/dashboard");
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-6">
      <div className="w-full max-w-sm">
        <h1 className="mb-1 text-2xl font-bold tracking-tight">
          Signal<span className="text-sky-400">Forge</span>
        </h1>
        <p className="mb-6 text-sm text-slate-400">
          {mode === "login"
            ? "Sign in to your organization"
            : "Create an organization and its first admin"}
        </p>

        <form onSubmit={submit} className="card space-y-4">
          {mode === "register" && (
            <>
              <div>
                <label className="label" htmlFor="orgName">Organization name</label>
                <input id="orgName" className="input" value={orgName}
                  onChange={(e) => setOrgName(e.target.value)} required />
              </div>
              <div>
                <label className="label" htmlFor="orgSlug">Slug</label>
                <input id="orgSlug" className="input" value={orgSlug} placeholder="acme"
                  onChange={(e) => setOrgSlug(e.target.value)} required />
              </div>
              <div>
                <label className="label" htmlFor="fullName">Your name</label>
                <input id="fullName" className="input" value={fullName}
                  onChange={(e) => setFullName(e.target.value)} required />
              </div>
            </>
          )}

          <div>
            <label className="label" htmlFor="email">Email</label>
            <input id="email" type="email" className="input" value={email}
              onChange={(e) => setEmail(e.target.value)} required />
          </div>

          <div>
            <label className="label" htmlFor="password">Password</label>
            <input id="password" type="password" className="input" value={password}
              onChange={(e) => setPassword(e.target.value)} required
              minLength={mode === "register" ? 12 : 1} />
            {mode === "register" && (
              <p className="mt-1 text-xs text-slate-500">
                At least 12 characters. Length beats composition rules.
              </p>
            )}
          </div>

          {error != null && <ErrorNotice error={error} />}

          <button type="submit" className="btn-primary w-full" disabled={busy}>
            {busy ? "Working…" : mode === "login" ? "Sign in" : "Create organization"}
          </button>

          <button type="button" className="w-full text-xs text-slate-400 hover:text-slate-200"
            onClick={() => { setMode(mode === "login" ? "register" : "login"); setError(null); }}>
            {mode === "login"
              ? "First time here? Create an organization"
              : "Already have an account? Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
