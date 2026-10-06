# E-extensions

Personal Mihon/Suwayomi extension repository. It maintains E-Hentai/ExHentai, Komiic, and the recovered Super Hentais source.

## Mihon repository

Current Mihon versions use:

```text
https://raw.githubusercontent.com/HyperionHXH/E-extensions/repo/index.json
```

Older Mihon/Suwayomi builds can use:

```text
https://raw.githubusercontent.com/HyperionHXH/E-extensions/repo/index.min.json
```

The `main` branch contains the reviewed source modules and build infrastructure. The `repo` branch contains published metadata and artifacts. APKs keep the same signing key between releases so Mihon can update an installed extension. The included GitHub Actions workflow separates the signing build from the repository-writing publish job.

Every push and pull request runs a tracked-file credential scan. Signing material is supplied only through GitHub Actions secrets and is never committed to either branch.

## E-Hentai features

- Normal search and account favorites.
- **My watched tags** uses the selected mirror's authoritative `/watched` feed, the same endpoint used by JHenTai. The server applies the account's watched/hidden-tag rules and ordering; if the site returns an empty watched feed despite enabled tags in `/mytags`, the extension automatically falls back to batched watched-tag searches, applies hidden tags, and keeps newest galleries first.
- **JHenTai-style browsing controls** are available in the Mihon filter panel: site search, watched-feed keyword/date constraints, favorite saved/publication ordering, and four ranklist choices directly in **Browse source** (yesterday/month/year/all-time), plus category/rating/language switches and server-side filter bypasses. The date field uses `YYYY-MM-DD` and is sent as the site's `seek` cursor. The site search query is also applied when a non-search browse source is selected, so it can narrow favorites, watched results, and ranklists.
- Mihon now exposes the E-Hentai front page as **Latest** and the site's `/popular` feed as **Popular**; both retain the server's pagination cursor instead of sorting gallery IDs locally.
- Category, rating, language, page-count, expunged, and torrent filters.
- Gallery/page retries, request pacing, and optional image URL pre-resolution.

Mihon/Suwayomi/Komikku provide the reader UI, read history, progress, and download queue. An extension can provide gallery metadata, chapters, pages, filters, and image requests, but cannot replace those host-level screens or read their local history.

The extension API exposes only the host's fixed Popular, Latest, and Search entry points. Extra JHenTai pages such as Watched, Favorites, and Toplists are therefore exposed through the **Browse source** search filter; a source cannot add new top-level toolbar buttons or tabs.

The account favorites list is read from ExHentai so it remains available when E-Hentai's favorites endpoint returns a login redirect; gallery details/pages still use the selected mirror. If a mirror returns a temporary rate-limit page, the extension reports it instead of silently showing an empty result.

For Suwayomi with Clash, the browser session and the extension must use the same network exit. If the watched feed returns a login page while the same cookies work in the browser, set **Proxy URL** in the source settings to Clash's HTTP endpoint (usually `http://127.0.0.1:7890`) and restart Suwayomi. Leave it empty to inherit the application's proxy; HTTP(S) and SOCKS proxy URLs are supported.

## Login

Enter `ipb_member_id`, `ipb_pass_hash`, and `igneous` separately in the source settings. All values must come from the same browser session and network exit. ExHentai also requires account permission for that site.

No cookie value is stored in this repository or included in an APK.

## Komiic 登录

Komiic 的登录入口在插件设置中填写“登录邮箱”和“登录密码”。第一次读取正文图片时，插件调用 Komiic 官方 `POST /api/login`，由网站返回的 `komiic-access-token` cookie 决定账号和赞助额度；不会绕过每日图片限制。邮箱未验证、密码错误或账号额度用尽时，插件会显示对应的登录/额度错误。凭据只保存在 Mihon/Suwayomi 的本机私有设置中，不要提交到 GitHub。

当前主分支版本：E-Hentai `1.6.35`、Komiic `1.6.11`、Super Hentais `1.6.1`。正式 APK/JAR 和校验清单只由 Actions 签名并发布到 `repo` 分支与 GitHub Releases。

## Build

```powershell
C:\Temp\gradle-9.7.0\bin\gradle.bat :src:en:ehentai:assembleRelease :src:en:ehentai:assembleDebug :src:en:ehentai:lintRelease --no-daemon
```

For Komiic local checks:

```powershell
$env:ANDROID_HOME = "C:\Android\sdk"
./gradlew.bat :src:zh:komiic:assembleDebug :src:zh:komiic:lintRelease --no-daemon
```

Artifacts are written under the corresponding source's `build/outputs` directories.
