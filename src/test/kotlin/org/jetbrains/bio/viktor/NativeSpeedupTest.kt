package org.jetbrains.bio.viktor

import org.junit.Test
import kotlin.test.assertTrue

class NativeSpeedupTest {
    @Test
    fun nativeSpeedupEnabled() {
        assertTrue(
            Loader.nativeLibraryLoaded,
            """
Native optimizations disabled.
If running as a project: have you added -Djava.library.path=./build/libs to JVM options?
If running from a JAR: is your system supported?
"""
        )
    }
}