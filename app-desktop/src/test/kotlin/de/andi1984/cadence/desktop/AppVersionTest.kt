package de.andi1984.cadence.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `resolveAppVersion` is the one place the version in Settings is decided: the build-compiled
 * `DesktopBuildConfig.VERSION_NAME` when the build carries one — every packaged .deb/.dmg/.msi,
 * which is launched without the `CADENCE_VERSION_NAME` environment it was built with — and
 * "0.0.0-dev" only for a `:app-desktop:run` from source (#217).
 */
class AppVersionTest {

    @Test
    fun `a build without a compiled-in version falls back to the dev version`() {
        assertEquals(DEV_VERSION_NAME, resolveAppVersion(null))
    }

    @Test
    fun `a blank compiled-in version falls back to the dev version`() {
        assertEquals(DEV_VERSION_NAME, resolveAppVersion(""))
        assertEquals(DEV_VERSION_NAME, resolveAppVersion("   "))
    }

    @Test
    fun `the compiled-in version is shown as it was built`() {
        assertEquals("1.2.3", resolveAppVersion("1.2.3"))
        assertEquals("1.2.3-dev", resolveAppVersion("1.2.3-dev"))
    }

    @Test
    fun `surrounding whitespace is not part of the version`() {
        assertEquals("1.2.3", resolveAppVersion("  1.2.3\n"))
    }
}
