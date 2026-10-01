package com.healthsync

object SyncPayload {
    private val rawTypesNeededByBackend = setOf(
        "ExerciseSessionRecord",
        "WeightRecord",
        "BodyFatRecord",
        "LeanBodyMassRecord",
        "BodyWaterMassRecord",
    )

    fun compactForBackground(snapshot: HealthSnapshot): HealthSnapshot {
        val compactRaw = snapshot.rawRecords.filterKeys { it in rawTypesNeededByBackend }
        return snapshot.copy(rawRecords = compactRaw)
    }
}
