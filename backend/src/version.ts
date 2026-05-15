import type { Env, VersionResponse } from './types';

interface GhRelease {
  tag_name: string;
  name: string;
  body: string;
  draft: boolean;
  prerelease: boolean;
  published_at: string;
  assets: Array<{
    name: string;
    browser_download_url: string;
    size: number;
  }>;
}

/**
 * GitHub Releases API 로 최신 release 정보 조회 → VersionResponse 변환.
 * public repo 라 인증 불필요 (rate limit: 60 req/hr unauthenticated).
 *
 * tag_name 규칙: `v{major}.{minor}.{patch}` → versionCode = major*10000 + minor*100 + patch
 *   v0.1.0  → 100
 *   v1.2.3  → 10203
 *   v0.0.10 → 10
 */
export async function fetchLatestVersion(env: Env): Promise<VersionResponse> {
  const url = `https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/releases/latest`;
  const headers: Record<string, string> = {
    'User-Agent': 'reflect-backend',
    'Accept': 'application/vnd.github+json',
  };
  if (env.GITHUB_TOKEN) headers['Authorization'] = `Bearer ${env.GITHUB_TOKEN}`;
  // CF Workers Request 타입엔 'cache' 가 없음 — fetch 옵션에서 빼고 헤더로 우회.
  headers['Cache-Control'] = 'no-store';
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`GitHub API ${res.status}: ${await res.text()}`);
  const release = (await res.json()) as GhRelease;

  const apkAsset = release.assets.find((a) => a.name.endsWith('.apk'));
  if (!apkAsset) throw new Error('No APK asset in latest release');

  return {
    latest_version: release.tag_name,
    latest_version_code: tagToVersionCode(release.tag_name),
    apk_url: apkAsset.browser_download_url,
    changelog: release.body || '',
    force_update: false,
  };
}

function tagToVersionCode(tag: string): number {
  const m = tag.replace(/^v/, '').split('.').map((n) => parseInt(n, 10) || 0);
  const [major = 0, minor = 0, patch = 0] = m;
  return major * 10000 + minor * 100 + patch;
}
