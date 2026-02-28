package top.charles7c.mavendependencyupgrader

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import top.charles7c.mavendependencyupgrader.util.VersionUtils
import top.charles7c.mavendependencyupgrader.model.UpdateType

class MavenDependencyUpgraderTest : BasePlatformTestCase() {

    fun testVersionCompare_equal() {
        assertEquals(0, VersionUtils.compare("1.2.3", "1.2.3"))
    }

    fun testVersionCompare_newer() {
        assertTrue(VersionUtils.compare("2.0.0", "1.9.9") > 0)
        assertTrue(VersionUtils.compare("1.3.0", "1.2.9") > 0)
        assertTrue(VersionUtils.compare("1.2.4", "1.2.3") > 0)
    }

    fun testVersionCompare_older() {
        assertTrue(VersionUtils.compare("1.0.0", "2.0.0") < 0)
    }

    fun testDetermineUpdateType_patch() {
        assertEquals(UpdateType.PATCH, VersionUtils.determineUpdateType("1.2.3", "1.2.4"))
    }

    fun testDetermineUpdateType_minor() {
        assertEquals(UpdateType.MINOR, VersionUtils.determineUpdateType("1.2.3", "1.3.0"))
    }

    fun testDetermineUpdateType_major() {
        assertEquals(UpdateType.MAJOR, VersionUtils.determineUpdateType("1.2.3", "2.0.0"))
    }

    fun testDetermineUpdateType_upToDate() {
        assertEquals(UpdateType.UP_TO_DATE, VersionUtils.determineUpdateType("1.2.3", "1.2.3"))
    }

    fun testIsStable() {
        assertTrue(VersionUtils.isStable("1.2.3"))
        assertTrue(VersionUtils.isStable("6.0.0"))
        assertFalse(VersionUtils.isStable("1.2.3-SNAPSHOT"))
        assertFalse(VersionUtils.isStable("2.0.0.alpha1"))
        assertFalse(VersionUtils.isStable("3.0.0.RC1"))
    }
}
