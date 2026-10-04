package com.healthsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord

class PrivateHealthManager(private val context: Context) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(HealthPermission.getReadPermission(HeartRateRecord::class))

    suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(permissions)

    fun availability(): HealthConnectManager.Availability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectManager.Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectManager.Availability.INSTALL_OR_UPDATE_REQUIRED
            else -> HealthConnectManager.Availability.UNAVAILABLE
        }
    }

    fun installOrUpdateIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("market://details?id=com.google.android.apps.healthdata")
        setPackage("com.android.vending")
    }
}
