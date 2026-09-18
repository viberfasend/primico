---
name: releasing
description: How Primico is built, tested in CI and released — build.sh, the four GitHub workflows and their dispatch inputs, version derivation from Conventional Commits, and the debug and release signing keys. Use when cutting a release, touching anything under .github/, or changing versioning or signing.
---

# CI / releases


**Only the cheap workflows run on their own.** `ci.yml` runs the test command at
the top of Commands on Linux for every pull request and every push to `main` — the repository
is public, so Linux minutes are free and unlimited, and `main`'s branch protection requires
that check. Everything that spends money stays `workflow_dispatch`: `android.yml`,
`desktop.yml` and `release.yml` have no `push`, no `pull_request`, no `schedule`. They used to
run on every push to **any** branch back when the repository was private and minutes were
billed; a single push started four runners, two of them at macOS's 10x and Windows's 2x
multipliers. **Still verify locally before pushing**: the suite is roughly five seconds of test
time, CI only confirms what a laptop already knows, and if it takes minutes something hangs —
see the note below. Don't give `desktop.yml` or `release.yml` an automatic trigger without being
asked for it outright; a macOS leg on every PR is the bill this rule exists to prevent.
`dependabot.yml` opens one grouped PR per ecosystem (Gradle, Actions) weekly, and `ci.yml` is
what validates them.

What each one does:

- `ci.yml` — the test command, Linux only, uploading the test reports on failure. No APK: that
  is 31 tasks that run no test.
- `pages.yml` — deploys `site/` (the landing page, plain HTML, no build step) to GitHub Pages on
  a push to `main` that touches it. The second self-running workflow, and for the same reason:
  a Linux runner is free here, and a page that only deploys when clicked goes stale.
- `android.yml` — tests, then `build.sh apk`, uploading both APKs as artifacts. Dispatch only.

Two backstops sit under all four, because the failure that prompted them cost hours rather
than minutes: **every `Test` task has a five-minute `timeout`** (the `subprojects` block in the
root `build.gradle.kts`) and **every job has a `timeout-minutes`**. GitHub's default job limit
is six hours, so a test that hangs instead of failing runs out the afternoon and reports
nothing. Five minutes is two orders of magnitude above what the suite needs, so it can only
ever catch a hang.

Caching is `gradle/actions/setup-gradle@v4` and nothing else. It caches `~/.gradle` — the
dependency cache, the wrapper and the **build cache**, which `gradle.properties` now switches
on — so the hand-rolled `actions/cache` step that used to sit beside it restored the same
directory a second time, with a `transforms-` glob that matched nothing, and has been removed
along with the `find ~/.gradle/caches -delete` step that pruned what the action manages.
- `desktop.yml` — tests, then `build.sh desktop`, over a matrix built from an `os` **input**
  that defaults to `ubuntu-latest` alone rather than fanning out to three runners; pass `all`
  for all three. jpackage runs on the target OS, so there is no cross-compiling a `.dmg` from
  Linux, and each leg installs the packaging tool its OS lacks (`fakeroot`/`rpm` on Linux, the
  WiX Toolset on Windows; macOS's `hdiutil` needs nothing extra). A `.dmg` or an `.msi` is the
  only artefact a Linux laptop genuinely cannot produce — that is the whole remaining case for
  the runner.
- `release.yml` — the only workflow that publishes anything, and **the only one that refuses to
  run off `main`**: it creates the tag from the commit it was dispatched on, so a run started on
  a branch would publish a release pointing at unmerged work. Its `targets` **input** decides
  what gets built, and defaults to `android+deb` — the APKs and the Linux `.deb`, one Linux
  runner each, which is what a Primico release actually ships today. `android` and `deb` narrow
  that to one half; `all` adds the rpm, the tarball, the `.dmg` and the `.msi`, and with them the
  macOS and Windows runners at 10x and 2x. The download list in the release body is generated
  from the files that actually landed in `dist/`, so a run that packaged no `.msi` publishes no
  link to one. `dry-run` builds and tests everything and publishes nothing.

  Four jobs: `preflight` derives the version **once** (both build jobs read it from
  `needs.preflight.outputs`, so the two can never name one build two versions) and is where the
  branch guard and the "no commits since the last tag" guard live; `build-android` and
  `build-desktop` run in parallel; `publish` collects every `release-assets-*` artifact with one
  `pattern` download, because which artifacts exist depends on the input. The desktop job names
  `deb` outright rather than `desktop` — `build.sh` demotes a format to a warning only when an
  alias implied it, and a release that quietly ships without the `.deb` is exactly the failure
  the job exists to catch.

Since `build.sh` is what all three call, a release can equally be cut entirely on a laptop: `bash
.github/scripts/build.sh apk deb` stages `dist/`, then `gh release create … dist/*`.

The version is **derived, never edited**. `.github/scripts/next-version.sh` reads the
Conventional Commit subjects since the last `v*` tag: a `!` or a `BREAKING CHANGE:` footer bumps
major, any `feat:` bumps minor, anything else bumps patch, so a release always carries a version
that describes what went into it. With no tag yet the first release is `1.0.0`. The script
writes `version`/`version_code`/`notes` as step outputs; `app-android/build.gradle.kts` reads
`CADENCE_VERSION_NAME`/`CADENCE_VERSION_CODE` from the environment and falls back to `0.0.0-dev`
locally. `versionCode` is `major * 10000 + minor * 100 + patch`. Run the script locally to see
what releasing now would publish:

```bash
bash .github/scripts/next-version.sh    # prints the outputs when GITHUB_OUTPUT is unset
```

The release action creates the tag from the commit it ran on, so the next run measures from
there. `releases/latest/download/primico-debug.apk` still serves the newest published build,
because each release is published with `make_latest` — it now moves when someone releases, not
when someone merges. Debug and release use different application IDs (`.debug` suffix) and
install side by side.

**The debug key is committed (`app-android/debug.keystore`) and must stay that way.** Android installs a
build over an existing app only when both carry the same signing certificate, and AGP invents a
fresh `~/.android/debug.keystore` wherever none exists — on a CI runner, that is every single
run. Releases up to v1.1.2 therefore each had their own key and could not update one another;
the phone just said "App not installed". Pinning the key is the fix, so don't move it back
behind `.gitignore` or let the debug `signingConfig` fall back to AGP's default.
`.github/scripts/check-signing.sh` runs in CI and fails the build if the debug APK's certificate
stops matching the committed keystore.

**The release APK is signed with a real key since 3.0, and the README's permanent link points
at it.** The four `CADENCE_KEYSTORE_BASE64` / `CADENCE_KEYSTORE_PASSWORD` / `CADENCE_KEY_ALIAS` /
`CADENCE_KEY_PASSWORD` secrets carry it into `android.yml` and `release.yml`; the keystore itself
lives outside every checkout (`~/.cadence-release/` by default) and `tools/release-signing-wizard.sh`
is how it was minted and how the secrets are (re)set — re-running it reuses the file and never
overwrites a key. Losing that file means every install has to be uninstalled once, the way the
pre-3.0 debug-signed installs had to be. Without the secrets the release build still falls back
to the debug key so `assembleRelease` works on any laptop, but `check-signing.sh` now *fails*
when `CADENCE_KEYSTORE` is set and the release APK nevertheless carries the debug certificate —
a release cut that way could not update anyone. The debug APK stays debug-signed and public by
construction: anyone can build one that installs over it, which is why `SECURITY.md` tells
users to install the release APK.
