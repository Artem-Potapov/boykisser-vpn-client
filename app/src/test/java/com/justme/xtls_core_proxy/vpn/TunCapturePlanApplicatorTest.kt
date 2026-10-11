package com.justme.xtls_core_proxy.vpn

import android.content.pm.PackageManager
import com.justme.xtls_core_proxy.split.SplitTunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunCapturePlanApplicatorTest {

    private sealed interface Operation {
        data class SetSession(val name: String) : Operation
        data class SetMtu(val mtu: Int) : Operation
        data class AddAddress(val address: String, val prefixLength: Int) : Operation
        data class AddRoute(val destination: String, val prefixLength: Int) : Operation
        data class AddDnsServer(val address: String) : Operation
        data class AddAllowedApplication(val packageName: String) : Operation
        data class AddDisallowedApplication(val packageName: String) : Operation
    }

    private class RecordingTarget : TunBuilderTarget {
        val operations = mutableListOf<Operation>()

        override fun setSession(name: String) {
            operations += Operation.SetSession(name)
        }

        override fun setMtu(mtu: Int) {
            operations += Operation.SetMtu(mtu)
        }

        override fun addAddress(address: String, prefixLength: Int) {
            operations += Operation.AddAddress(address, prefixLength)
        }

        override fun addRoute(destination: String, prefixLength: Int) {
            operations += Operation.AddRoute(destination, prefixLength)
        }

        override fun addDnsServer(address: String) {
            operations += Operation.AddDnsServer(address)
        }

        override fun addAllowedApplication(packageName: String) {
            operations += Operation.AddAllowedApplication(packageName)
        }

        override fun addDisallowedApplication(packageName: String) {
            operations += Operation.AddDisallowedApplication(packageName)
        }
    }

    private class MissingPackageTarget(
        private val missingAllowed: Set<String>,
        private val missingDisallowed: Set<String>,
    ) : TunBuilderTarget {
        val allowed = mutableListOf<String>()
        val disallowed = mutableListOf<String>()

        override fun setSession(name: String) = Unit
        override fun setMtu(mtu: Int) = Unit
        override fun addAddress(address: String, prefixLength: Int) = Unit
        override fun addRoute(destination: String, prefixLength: Int) = Unit
        override fun addDnsServer(address: String) = Unit

        override fun addAllowedApplication(packageName: String) {
            if (packageName in missingAllowed) {
                throw PackageManager.NameNotFoundException(packageName)
            }
            allowed += packageName
        }

        override fun addDisallowedApplication(packageName: String) {
            if (packageName in missingDisallowed) {
                throw PackageManager.NameNotFoundException(packageName)
            }
            disallowed += packageName
        }
    }

    private class PropagatingTarget(
        private val allowedFailure: (String) -> Throwable? = { null },
        private val disallowedFailure: (String) -> Throwable? = { null },
    ) : TunBuilderTarget {
        override fun setSession(name: String) = Unit
        override fun setMtu(mtu: Int) = Unit
        override fun addAddress(address: String, prefixLength: Int) = Unit
        override fun addRoute(destination: String, prefixLength: Int) = Unit
        override fun addDnsServer(address: String) = Unit

        override fun addAllowedApplication(packageName: String) {
            allowedFailure(packageName)?.let { throw it }
        }

        override fun addDisallowedApplication(packageName: String) {
            disallowedFailure(packageName)?.let { throw it }
        }
    }

    private fun plan(
        allowed: List<String> = listOf("com.example.b", "com.example.a"),
        disallowed: List<String> = listOf("com.example.z", "com.example.y"),
    ) = TunCapturePlan(
        sessionName = "xtls",
        mtu = 1280,
        addresses = listOf("10.7.0.1" to 32, "fd00:1:fd00:1::1" to 128),
        routes = listOf("0.0.0.0" to 0, "::" to 0),
        dnsServers = listOf("1.1.1.1", "2606:4700:4700::1111"),
        splitPlan = SplitTunnelPlan(
            allowedPackages = allowed,
            disallowedPackages = disallowed,
        ),
    )

    @Test
    fun apply_recordsOperationsInPlanOrder() {
        val target = RecordingTarget()

        TunCapturePlanApplicator.apply(plan(), target) {}

        assertEquals(
            listOf(
                Operation.SetSession("xtls"),
                Operation.SetMtu(1280),
                Operation.AddAddress("10.7.0.1", 32),
                Operation.AddAddress("fd00:1:fd00:1::1", 128),
                Operation.AddRoute("0.0.0.0", 0),
                Operation.AddRoute("::", 0),
                Operation.AddDnsServer("1.1.1.1"),
                Operation.AddDnsServer("2606:4700:4700::1111"),
                Operation.AddAllowedApplication("com.example.b"),
                Operation.AddAllowedApplication("com.example.a"),
                Operation.AddDisallowedApplication("com.example.z"),
                Operation.AddDisallowedApplication("com.example.y"),
            ),
            target.operations,
        )
    }

    @Test
    fun targetInterface_hasNoEstablishOperation() {
        val methodNames = TunBuilderTarget::class.java.methods.map { it.name }
        assertFalse(methodNames.any { it.contains("establish", ignoreCase = true) })

        val recordingNames = RecordingTarget::class.java.declaredMethods.map { it.name }
        assertFalse(recordingNames.any { it.contains("establish", ignoreCase = true) })
    }

    @Test
    fun apply_reportsEachMissingPackageOnceAndContinues() {
        val target = MissingPackageTarget(
            missingAllowed = setOf("com.example.missing.allowed"),
            missingDisallowed = setOf("com.example.missing.blocked"),
        )
        val missing = mutableListOf<String>()

        TunCapturePlanApplicator.apply(
            plan(
                allowed = listOf(
                    "com.example.kept.allowed",
                    "com.example.missing.allowed",
                    "com.example.later.allowed",
                ),
                disallowed = listOf(
                    "com.example.kept.blocked",
                    "com.example.missing.blocked",
                    "com.example.later.blocked",
                ),
            ),
            target,
        ) { packageName -> missing += packageName }

        assertEquals(
            listOf("com.example.missing.allowed", "com.example.missing.blocked"),
            missing,
        )
        assertEquals(
            listOf("com.example.kept.allowed", "com.example.later.allowed"),
            target.allowed,
        )
        assertEquals(
            listOf("com.example.kept.blocked", "com.example.later.blocked"),
            target.disallowed,
        )
    }

    @Test
    fun apply_propagatesNonNameNotFoundExceptionsFromAllowedApplications() {
        val failure = IllegalStateException("allowed boom")
        val target = PropagatingTarget(allowedFailure = { pkg ->
            if (pkg == "com.example.a") failure else null
        })

        val thrown = runCatching {
            TunCapturePlanApplicator.apply(
                plan(allowed = listOf("com.example.a", "com.example.later")),
                target,
            ) {}
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("allowed boom", thrown?.message)
    }

    @Test
    fun apply_propagatesNonNameNotFoundExceptionsFromDisallowedApplications() {
        val failure = IllegalArgumentException("disallowed boom")
        val target = PropagatingTarget(disallowedFailure = { pkg ->
            if (pkg == "com.example.z") failure else null
        })

        val thrown = runCatching {
            TunCapturePlanApplicator.apply(
                plan(disallowed = listOf("com.example.z", "com.example.later")),
                target,
            ) {}
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
        assertEquals("disallowed boom", thrown?.message)
    }
}
