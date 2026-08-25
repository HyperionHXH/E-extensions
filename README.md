# E-extensions

Personal Mihon/Suwayomi extension repository. It maintains E-Hentai, with E-Hentai and ExHentai available as mirrors of one source, and the recovered Super Hentais source.

## Mihon repository

Current Mihon versions use:

```text
https://raw.githubusercontent.com/HyperionHXH/E-extensions/repo/index.json
```

Older Mihon/Suwayomi builds can use:

```text
https://raw.githubusercontent.com/HyperionHXH/E-extensions/repo/index.min.json
```

The `main` branch contains only the two reviewed source modules and their required build infrastructure. The `repo` branch contains published metadata and artifacts. APKs keep the same signing key between releases so Mihon can update an installed extension. The included GitHub Actions workflow separates the signing build from the repository-writing publish job.

Every push and pull request runs a tracked-file credential scan. Signing material is supplied only through GitHub Actions secrets and is never committed to either branch.

## E-Hentai features

- Normal search and account favorites.
- **My watched tags** uses the selected mirror's authoritative `/watched` feed, matching JHenTai's weight, hidden-tag, and tag-alias behavior.
- Optional local include/exclude tags supplement the account rules.
- Category, rating, language, page-count, expunged, and torrent filters.
- Gallery/page retries, request pacing, and optional image URL pre-resolution.

The account favorites list is read from ExHentai so it remains available when E-Hentai's favorites endpoint returns a login redirect; gallery details/pages still use the selected mirror. If a mirror returns a temporary rate-limit page, the extension reports it instead of silently showing an empty result.

For Suwayomi with Clash, the browser session and the extension must use the same network exit. If the watched feed returns a login page while the same cookies work in the browser, set **Proxy URL** in the source settings to Clash's HTTP endpoint (usually `http://127.0.0.1:7890`) and restart Suwayomi. Leave it empty to inherit the application's proxy; HTTP(S) and SOCKS proxy URLs are supported.

## Login

Enter `ipb_member_id`, `ipb_pass_hash`, and `igneous` separately in the source settings. All values must come from the same browser session and network exit. ExHentai also requires account permission for that site.

No cookie value is stored in this repository or included in an APK.

## Build

```powershell
C:\Temp\gradle-9.7.0\bin\gradle.bat :src:en:ehentai:assembleRelease :src:en:ehentai:assembleDebug :src:en:ehentai:lintRelease --no-daemon
```

Artifacts are written under `src/en/ehentai/build/outputs/apk/release` and `src/en/ehentai/build/outputs/jar/release`.
