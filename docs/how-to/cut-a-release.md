---
title: Cut a release
description: Preview the next version, build every artefact with build.sh, and publish — either from a laptop with gh release create or by dispatching release.yml — with the right signing key.
sidebar:
  order: 7
---

A Primico release is a GitHub release carrying two APKs and the desktop packages. The same script
builds them on a laptop and on a runner, so you can publish from either. This guide is the human
summary of the `releasing` skill
([`.claude/skills/releasing/SKILL.md`](../../.claude/skills/releasing/SKILL.md)). Every task and
workflow input is in the [build and CI reference](../reference/build-and-ci.md).

## Prerequisites

- You're on **`main`**, up to date, with a clean working tree. A release is always cut from
  `main`.
- **JDK 17+** and the **Android SDK** (for the APKs); `fakeroot` for a `.deb` and `rpmbuild` for an
  `.rpm` on Linux.
- **`gh`**, authenticated against the repository, for publishing.
- For an official build: the **release keystore** and its passwords, and the two
  **`CADENCE_NEON_*`** sync endpoints. Without the keystore the release APK falls back to the
  debug key, and without the endpoints the build has no sync. Both are fine for a test build and
  wrong for a public one.

## How the version is decided

You never edit a version. `.github/scripts/next-version.sh` reads the Conventional Commit subjects
since the last `v*` tag and bumps:

| Commits since the last tag contain… | Bump |
|---|---|
| a `!` before the colon (`feat!:`, `fix(sync)!:`) or a `BREAKING CHANGE:` footer | major |
| any `feat:` | minor |
| anything else | patch |

`versionCode` is `major × 10000 + minor × 100 + patch`. Preview what releasing now would publish:

```bash
bash .github/scripts/next-version.sh
```

```
skip=false
version=4.1.0
tag=v4.1.0
version_code=40100
bump=minor
previous_tag=v4.0.0
notes<<NOTES_EOF
### Features
…
NOTES_EOF
```

The `notes` block is the release notes, rendered from the same commits. With no commits since the
last tag it prints `skip=true` instead, and `release.yml` refuses to run.

## Option A: release from a laptop

`build.sh` is the whole build: it derives the version, runs **one** Gradle invocation for every
artefact, stages the files in `dist/` under the names the download links use, and checks the
APK signatures.

1. **See the plan.** Nothing is built:

   ```bash
   bash .github/scripts/build.sh apk deb --dry-run
   ```

   ```
   ==> Preflight
       java 17, linux
       version 4.1.0 (40100) — derived from commits

   ==> Plan
       targets:  apk deb
       version:  4.1.0 (40100)
       gradle:   ./gradlew :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm :core:testDebugUnitTest :ui:testDebugUnitTest assembleDebug assembleRelease :app-desktop:packageDeb --console=plain --stacktrace
       output:   dist/
   ```

   `apk deb` is what a release ships today. With no targets it builds `all`: the APKs plus every
   desktop format **this OS** can package (`deb rpm tar` on Linux, `dmg` on macOS, `msi` on
   Windows). A format whose tool is missing is skipped with a warning when an alias implied it,
   and is a hard error when you named it.

2. **Build with the release key and the sync endpoints:**

   ```bash
   export CADENCE_KEYSTORE_PASSWORD=… CADENCE_KEY_ALIAS=… CADENCE_KEY_PASSWORD=…
   export CADENCE_NEON_DATA_API_URL='https://<endpoint>.apirest.<region>.aws.neon.tech/neondb/rest/v1'
   export CADENCE_NEON_AUTH_URL='https://<endpoint>.neonauth.<region>.aws.neon.tech/neondb/auth'

   bash .github/scripts/build.sh apk deb --clean \
       --keystore ~/.cadence-release/cadence-release.jks
   ```

   It runs the tests first (`--skip-tests` skips them), then stages
   `dist/primico-debug.apk`, `dist/primico-release.apk` and the `.deb` under its jpackage name,
   then runs `check-signing.sh`. It ends by printing the publish command.

3. **Publish:**

   ```bash
   gh release create v4.1.0 dist/* --title "Primico 4.1.0" --generate-notes
   ```

   Use the exact line `build.sh` printed. The tag is created on the default branch, which is why
   you release from an up-to-date `main`. The permanent link
   `releases/latest/download/primico-release.apk` now serves the new build.

## Option B: release from GitHub Actions

[`release.yml`](../../.github/workflows/release.yml) runs the same script on runners, and it's the
only workflow that publishes. It refuses to run from any branch but `main`, and refuses when
there are no commits since the last tag.

```bash
# build and test everything, publish nothing
gh workflow run release.yml --ref main -f targets=android+deb -f dry-run=true

# the real thing
gh workflow run release.yml --ref main -f targets=android+deb
```

| `targets` | Builds | Runners |
|---|---|---|
| `android+deb` (default) | both APKs and the Linux `.deb`, which is a normal release | two Linux |
| `android` | the APKs only | one Linux |
| `deb` | the `.deb` only | one Linux |
| `all` | plus `.rpm`, the tarball, `.dmg` and `.msi` | adds a macOS (10× minutes) and a Windows (2×) runner |

The release body's download list is generated from the files that actually landed, so a run
without an `.msi` publishes no link to one.

:::caution[Only ci.yml and pages.yml run on their own]
`android.yml`, `desktop.yml` and `release.yml` are `workflow_dispatch` only: they have no
`push`, `pull_request` or `schedule` trigger. Keep it that way unless the maintainer explicitly
asks otherwise. A macOS leg on every pull request is the bill this rule prevents. To try a
platform build without releasing, dispatch `desktop.yml` (input `os`: `ubuntu-latest` by
default, or `macos-latest`, `windows-latest`, `all`) or `android.yml`.
:::

## Signing: two keys, two rules

- **The debug key is committed** (`app-android/debug.keystore`) and must stay committed. Android
  only installs a build over another one signed with the same certificate, and without a pinned
  key every CI run would invent a new one. Up to v1.1.2 that happened, and phones said "App not
  installed". `check-signing.sh` fails the build if the debug APK stops matching the committed
  keystore.
- **The release key never enters a checkout.** It lives outside the repository
  (`~/.cadence-release/` by default) and reaches CI through four secrets:
  `CADENCE_KEYSTORE_BASE64`, `CADENCE_KEYSTORE_PASSWORD`, `CADENCE_KEY_ALIAS` and
  `CADENCE_KEY_PASSWORD`. When `CADENCE_KEYSTORE` is set but the release APK still carries the
  debug certificate, `check-signing.sh` fails, because a release built that way could update
  nobody.

To mint the keystore, or to (re)set all six secrets (the four signing ones plus
`CADENCE_NEON_DATA_API_URL` and `CADENCE_NEON_AUTH_URL`), run the wizard. It needs `keytool`, an
authenticated `gh` and `base64`:

```bash
bash tools/release-signing-wizard.sh
```

Re-running is safe: it reuses an existing keystore and never overwrites one. Passphrases are
never written anywhere.

:::danger[Losing the release keystore]
If the keystore file is lost, no future release can install over existing installs. Every user
would have to uninstall once (after exporting a backup) and install again. Back the file up
somewhere that isn't the repository.
:::

## Verify

- `gh release view v4.1.0` lists the assets, and the release is marked **Latest**.
- On a phone with the previous release installed, open
  `https://github.com/viberfasend/primico/releases/latest/download/primico-release.apk`. It
  should install **over** the old version and keep the tasks.
- In the new build's Settings, the Sync section shows a sign-in form. If it says the build has no
  sync server configured, the `CADENCE_NEON_*` variables were missing at build time.

## Related

- [Build and CI reference](../reference/build-and-ci.md): every workflow, input and `build.sh`
  flag.
- [Configuration reference](../reference/configuration.md): every `CADENCE_*` variable.
- [Self-hosting sync](../self-hosting.md): the endpoints a fork's release is built against.
