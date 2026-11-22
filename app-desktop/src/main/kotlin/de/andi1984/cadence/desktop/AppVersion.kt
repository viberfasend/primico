package de.andi1984.cadence.desktop

/**
 * The version Settings shows when the build carries none of its own — a plain
 * `:app-desktop:run` from source, where `CADENCE_VERSION_NAME` was never exported.
 */
const val DEV_VERSION_NAME = "0.0.0-dev"

/**
 * The version Settings reports: what this build was compiled with
 * ([DesktopBuildConfig.VERSION_NAME], written by `:app-desktop`'s `generateDesktopBuildConfig`
 * task from `CADENCE_VERSION_NAME` at *build* time), or [DEV_VERSION_NAME] when the build
 * carries none. It cannot be read from the environment at run time — an installed
 * .deb/.dmg/.msi is launched without the shell environment the build ran in, which is how
 * every packaged build used to report 0.0.0-dev (#217).
 */
fun resolveAppVersion(buildVersionName: String?): String =
    buildVersionName?.trim()?.takeIf { it.isNotEmpty() } ?: DEV_VERSION_NAME
