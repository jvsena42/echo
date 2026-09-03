package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportedHostTest {

    @Test
    fun `the two shipped rows are recognised`() {
        assertEquals(SupportedHost.LinuxX64, hostSupport("Linux", "amd64"))
        assertEquals(SupportedHost.LinuxX64, hostSupport("Linux", "x86_64"))
        assertEquals(SupportedHost.MacArm64, hostSupport("Mac OS X", "aarch64"))
        // A JVM that reports arm64 rather than aarch64 is the same machine and the same row.
        assertEquals(SupportedHost.MacArm64, hostSupport("Mac OS X", "arm64"))
    }

    @Test
    fun `hosts with no native row are refused`() {
        // The three the README names, and the reason each is absent is a decision rather than a gap.
        assertNull(hostSupport("Mac OS X", "x86_64"))
        assertNull(hostSupport("Windows 11", "amd64"))
        assertNull(hostSupport("Linux", "aarch64"))
    }

    @Test
    fun `an Intel Mac is told about Rosetta rather than about the network`() {
        val message = unsupportedHostMessage("Mac OS X", "x86_64")
        assertTrue("Apple Silicon" in message, message)
        assertTrue("Rosetta" in message, message)
    }

    @Test
    fun `windows says it is deferred, not broken`() {
        assertTrue("Windows is not a target yet" in unsupportedHostMessage("Windows 11", "amd64"))
    }

    /**
     * The whole reason [ExitCode.UnsupportedHost] exists, pinned as a fact rather than a claim.
     *
     * This is verbatim the shape JNA throws when no row on the classpath matches the host. Left to
     * the shared classifier it is not merely unclassified — `isNotFound()` matches the words "not
     * found" in it, so a machine that can never run this binary reports [ExitCode.NotFound], and
     * an agent reads that as "the deck you asked for does not exist" and moves on to the next one.
     */
    @Test
    fun `an unloadable library classifies as not_found, which is why the pre-check exists`() {
        val jnaFailure = UnsatisfiedLinkError(
            "Unable to load library 'pubkycore': Native library (darwin-x86-64/libpubkycore.dylib) " +
                "not found in resource path",
        )
        assertEquals(ExitCode.NotFound, ExitCode.of(jnaFailure))
    }
}
