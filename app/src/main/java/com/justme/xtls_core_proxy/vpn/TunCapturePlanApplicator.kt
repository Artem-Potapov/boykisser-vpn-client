package com.justme.xtls_core_proxy.vpn

import android.content.pm.PackageManager

internal object TunCapturePlanApplicator {
    fun apply(
        plan: TunCapturePlan,
        target: TunBuilderTarget,
        onMissingPackage: (String) -> Unit,
    ) {
        target.setSession(plan.sessionName)
        target.setMtu(plan.mtu)
        for ((address, prefixLength) in plan.addresses) {
            target.addAddress(address, prefixLength)
        }
        for ((destination, prefixLength) in plan.routes) {
            target.addRoute(destination, prefixLength)
        }
        for (address in plan.dnsServers) {
            target.addDnsServer(address)
        }
        for (packageName in plan.splitPlan.allowedPackages) {
            try {
                target.addAllowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                onMissingPackage(packageName)
            }
        }
        for (packageName in plan.splitPlan.disallowedPackages) {
            try {
                target.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                onMissingPackage(packageName)
            }
        }
    }
}
