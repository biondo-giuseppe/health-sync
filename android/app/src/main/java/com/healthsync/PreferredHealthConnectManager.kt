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
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

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
        val zone = ZoneId.systemDefault()
        val range = TimeRangeFilter.between(LocalDate.now().atStartOfDay(zone).toInstant(), now)
        val sleepRange = TimeRangeFilter.between(now.minusSeconds(86400), now)

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
        val common = baseline.copy(
            grantedPermissions = client.permissionController.getGrantedPermissions().filter { it in permissions }.sorted(),
            requestedRecordTypes = usefulNames.sorted(),
            rawRecords = baseline.rawRecords.filterKeys { it in usefulNames },
            extractionErrors = baseline.extractionErrors.filterKeys { it in usefulNames },
        )

        val zeppSteps = zepp?.get(StepsRecord.COUNT_TOTAL)
        if (zeppSteps == null || zeppSteps <= 0L) return common

        val zeppSleep = runCatching { readZeppSleep(sleepRange, zone) }.getOrNull()
        val zeppHrv = runCatching { readZeppHrv(sleepRange) }.getOrNull()
        val zeppStepsLastModifiedAt = runCatching { readLatestZeppStepsModifiedAt(range) }.getOrNull()

        return common.copy(
            steps = zeppSteps,
            caloriesActive = zepp[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toLong() ?: common.caloriesActive,
            caloriesTotal = zepp[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: common.caloriesTotal,
            heartRateAvg = zepp[HeartRateRecord.BPM_AVG]?.toInt() ?: common.heartRateAvg,
            heartRateResting = zepp[RestingHeartRateRecord.BPM_AVG]?.toInt() ?: common.heartRateResting,
            distanceMeters = zepp[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.toLong() ?: common.distanceMeters,
            activeMinutes = zepp[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes() ?: common.activeMinutes,
            sleepDurationMinutes = zeppSleep?.durationMinutes ?: common.sleepDurationMinutes,
            sleepStart = zeppSleep?.startLocal ?: common.sleepStart,
            sleepEnd = zeppSleep?.endLocal ?: common.sleepEnd,
            sleepStartUtc = zeppSleep?.startUtc ?: common.sleepStartUtc,
            sleepEndUtc = zeppSleep?.endUtc ?: common.sleepEndUtc,
            sleepDate = zeppSleep?.sleepDate ?: common.sleepDate,
            sleepStages = zeppSleep?.stages ?: common.sleepStages,
            hrvRmssdAvgMs = zeppHrv?.average ?: common.hrvRmssdAvgMs,
            hrvRmssdMedianMs = zeppHrv?.median ?: common.hrvRmssdMedianMs,
            hrvRmssdMinMs = zeppHrv?.min ?: common.hrvRmssdMinMs,
            hrvRmssdMaxMs = zeppHrv?.max ?: common.hrvRmssdMaxMs,
            hrvRmssdSampleCount = zeppHrv?.count ?: common.hrvRmssdSampleCount,
            selectedSummaryOrigin = ZEPP_PACKAGE,
            summaryDataOrigins = listOf(ZEPP_PACKAGE),
            zeppStepsLastModifiedAt = zeppStepsLastModifiedAt,
        )
    }

    private suspend fun readLatestZeppStepsModifiedAt(range: TimeRangeFilter): String? {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = setOf(DataOrigin(ZEPP_PACKAGE)),
                ascendingOrder = false,
                pageSize = 1,
            )
        ).records
        return records.firstOrNull()?.metadata?.lastModifiedTime?.toString()
    }

    private data class SleepPick(
        val durationMinutes: Long,
        val startLocal: String,
        val endLocal: String,
        val startUtc: String,
        val endUtc: String,
        val sleepDate: String,
        val stages: Map<String, Long>?,
    )

    private suspend fun readZeppSleep(range: TimeRangeFilter, zone: ZoneId): SleepPick? {
        val records = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = setOf(DataOrigin(ZEPP_PACKAGE)),
                ascendingOrder = true,
            )
        ).records
        val session = records.maxByOrNull { it.endTime } ?: return null
        val stages = session.stages.groupBy { it.stage }.mapValues { (_, values) ->
            values.sumOf { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() } / 60000
        }.mapKeys { (stage, _) ->
            when (stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
                SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
                SleepSessionRecord.STAGE_TYPE_REM -> "rem"
                SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
                else -> "unknown"
            }
        }
        val startLocal = ZonedDateTime.ofInstant(session.startTime, zone)
        val endLocal = ZonedDateTime.ofInstant(session.endTime, zone)
        return SleepPick(
            durationMinutes = (session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60000,
            startLocal = startLocal.toString(),
            endLocal = endLocal.toString(),
            startUtc = session.startTime.toString(),
            endUtc = session.endTime.toString(),
            sleepDate = endLocal.toLocalDate().toString(),
            stages = stages.takeIf { it.isNotEmpty() },
        )
    }

    private data class HrvPick(val average: Double, val median: Double, val min: Double, val max: Double, val count: Int)

    private suspend fun readZeppHrv(range: TimeRangeFilter): HrvPick? {
        val values = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateVariabilityRmssdRecord::class,
                timeRangeFilter = range,
                dataOriginFilter = setOf(DataOrigin(ZEPP_PACKAGE)),
                ascendingOrder = true,
            )
        ).records.map { it.heartRateVariabilityMillis }.sorted()
        if (values.isEmpty()) return null
        val middle = values.size / 2
        val median = if (values.size % 2 == 0) (values[middle - 1] + values[middle]) / 2.0 else values[middle]
        fun round1(value: Double) = kotlin.math.round(value * 10.0) / 10.0
        return HrvPick(round1(values.average()), round1(median), round1(values.first()), round1(values.last()), values.size)
    }

    companion object {
        const val ZEPP_PACKAGE = "com.huami.watch.hmwatchmanager"
    }
}
