package com.laurelid.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "verification_log")
data class VerificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val success: Boolean,
    val ageOver21: Boolean,
    val demoMode: Boolean,
    val error: String?,
    val tsMillis: Long,
    val totalSuccessCount: Long,
    val totalFailureCount: Long,
    val totalAgeOver21Count: Long,
    val totalAgeUnder21Count: Long,
    val totalDemoModeCount: Long,
)
