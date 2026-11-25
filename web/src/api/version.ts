import { apiFetch } from "./client";

export type VersionInfo = {
  name: string;
  version: string;
  gitSha: string;
};

export function parseVersionInfo(value: unknown): VersionInfo {
  if (typeof value === "object" && value !== null) {
    const v = value as Record<string, unknown>;
    if (
      typeof v.name === "string" &&
      typeof v.version === "string" &&
      typeof v.gitSha === "string"
    ) {
      return { name: v.name, version: v.version, gitSha: v.gitSha };
    }
  }
  throw new Error("API answered with an unexpected shape");
}

/** GET /api/v1/version - proves the web page can reach the API through the proxy. */
export function fetchVersion(signal?: AbortSignal): Promise<VersionInfo> {
  return apiFetch("/api/v1/version", parseVersionInfo, { signal });
}
