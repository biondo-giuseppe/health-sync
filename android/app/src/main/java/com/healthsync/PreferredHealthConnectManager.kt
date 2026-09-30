package com.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PreferredHealthConnectManager(private val context: Context) {
    private val delegate = HealthConnectManager(context)
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions get() = delegate.permissions
    fun availability() = delegate.availability()
    fun installOrUpdateIntent() = delegate.installOrUpdateIntent()
    fun managePermissionsIntent() = delegate.managePermissionsIntent()
    suspend fun hasPermissions() = delegate.hasPermissions()

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

        val zeppSteps = zepp?.get(StepsRecord.COUNT_TOTAL)
        if (zeppSteps == null || zeppSteps <= 0L) return baseline

        return baseline.copy(
            steps = zeppSteps,
            caloriesActive = zepp[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toLong() ?: baseline.caloriesActive,
            caloriesTotal = zepp[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: baseline.caloriesTotal,
            heartRateAvg = zepp[HeartRateRecord.BPM_AVG]?.toInt() ?: baseline.heartRateAvg,
            heartRateResting = zepp[RestingHeartRateRecord.BPM_AVG]?.toInt() ?: baseline.heartRateResting,
            distanceMeters = zepp[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toLong() ?: baseline.distanceMeters,
            activeMinutes = zepp[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes() ?: baseline.activeMinutes,
            selectedSummaryOrigin = ZEPP_PACKAGE,
            summaryDataOrigins = listOf(ZEPP_PACKAGE),
        )
    }

    companion object {
        const val ZEPP_PACKAGE = "com.huami.watch.hmwatchmanager"
    }
}
