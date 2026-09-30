package com.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PreferredHealthConnectManager(private val context: Context) {
    private val delegate = HealthConnectManager(context)
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    private val usefulRecordTypes = setOf(
        ActiveCaloriesBurnedRecord::class,
        BodyFatRecord::class,
        BodyWaterMassRecord::class,
        BoneMassRecord::class,
        DistanceRecord::class,
        ExerciseSessionRecord::class,
        HeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        HeightRecord::class,
        LeanBodyMassRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
        RestingHeartRateRecord::class,
        SleepSessionRecord::class,
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
        Vo2MaxRecord::class,
        WeightRecord::class,
    )

    val permissions = usefulRecordTypes.map { HealthPermission.getReadPermission(it) }.toSet() + setOf(
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    fun availability() = delegate.availability()
    fun installOrUpdateIntent() = delegate.installOrUpdateIntent()
    fun managePermissionsIntent() = delegate.managePermissionsIntent()

    suspend fun hasPermissions(): Boolean {
        if (availability() != HealthConnectManager.Availability.AVAILABLE) return false
        return client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
    }

    suspend fun readTodaySnapshot(): HealthSnapshot {
        val baseline = delegate.readTodaySnapshot()
        val now = Instant.now()
        val range = TimeRangeFilter.between(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant(), now)
        val zepp = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        HeartRateRecord.BPM_AVG,
                        RestingHeartRateRecord.BPM_AVG,
                        DistanceRecord.DISTANCE_TOTAL,
                        ExerciseSessionRecord.EXERCISE_DURATION_TOTAL,
                    ),
                    timeRangeFilter = range,
                    dataOriginFilter = setOf(DataOrigin(ZEPP_PACKAGE)),
                )
            )
        }.getOrNull()

        val usefulNames = usefulRecordTypes.map { it.java.simpleName }.toSet()
        val filteredRaw = baseline.rawRecords.filterKeys { it in usefulNames }
        val filteredErrors = baseline.extractionErrors.filterKeys { it in usefulNames }
        val zeppSteps = zepp?.get(StepsRecord.COUNT_TOTAL)

        val common = baseline.copy(
            grantedPermissions = client.permissionController.getGrantedPermissions().filter { it in permissions }.sorted(),
            requestedRecordTypes = usefulNames.sorted(),
            rawRecords = filteredRaw,
            extractionErrors = filteredErrors,
        )

        if (zeppSteps == null || zeppSteps <= 0L) return common

        return common.copy(
            steps = zeppSteps,
            caloriesActive = zepp[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toLong() ?: common.caloriesActive,
            caloriesTotal = zepp[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: common.caloriesTotal,
            heartRateAvg = zepp[HeartRateRecord.BPM_AVG]?.toInt() ?: common.heartRateAvg,
            heartRateResting = zepp[RestingHeartRateRecord.BPM_AVG]?.toInt() ?: common.heartRateResting,
            distanceMeters = zepp[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toLong() ?: common.distanceMeters,
            activeMinutes = zepp[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes() ?: common.activeMinutes,
            selectedSummaryOrigin = ZEPP_PACKAGE,
            summaryDataOrigins = listOf(ZEPP_PACKAGE),
        )
    }

    companion object {
        const val ZEPP_PACKAGE = "com.huami.watch.hmwatchmanager"
    }
}
