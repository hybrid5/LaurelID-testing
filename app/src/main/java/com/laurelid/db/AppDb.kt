package com.laurelid.db

import android.content.ContentValues
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.sqlite.SQLiteDatabase

@Database(
    entities = [VerificationEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun verificationDao(): VerificationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS verification_log_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        success INTEGER NOT NULL,
                        ageOver21 INTEGER NOT NULL,
                        demoMode INTEGER NOT NULL,
                        error TEXT,
                        tsMillis INTEGER NOT NULL,
                        totalSuccessCount INTEGER NOT NULL,
                        totalFailureCount INTEGER NOT NULL,
                        totalAgeOver21Count INTEGER NOT NULL,
                        totalAgeUnder21Count INTEGER NOT NULL,
                        totalDemoModeCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )

                val cursor = database.query(
                    "SELECT success, ageOver21, error, tsMillis FROM verification_log ORDER BY tsMillis ASC",
                )
                var successCount = 0L
                var failureCount = 0L
                var over21Count = 0L
                var under21Count = 0L
                val demoCount = 0L
                cursor.use {
                    while (it.moveToNext()) {
                        val success = it.getInt(0) == 1
                        val ageOver21 = it.getInt(1) == 1
                        val error = if (it.isNull(2)) null else it.getString(2)
                        val timestamp = it.getLong(3)

                        if (success) {
                            successCount++
                        } else {
                            failureCount++
                        }
                        if (ageOver21) {
                            over21Count++
                        } else {
                            under21Count++
                        }

                        val values = ContentValues().apply {
                            put("success", if (success) 1 else 0)
                            put("ageOver21", if (ageOver21) 1 else 0)
                            put("demoMode", 0)
                            put("error", error)
                            put("tsMillis", timestamp)
                            put("totalSuccessCount", successCount)
                            put("totalFailureCount", failureCount)
                            put("totalAgeOver21Count", over21Count)
                            put("totalAgeUnder21Count", under21Count)
                            put("totalDemoModeCount", demoCount)
                        }
                        database.insert(
                            "verification_log_new",
                            SQLiteDatabase.CONFLICT_REPLACE, // <-- was SupportSQLiteDatabase.CONFLICT_REPLACE
                            values,
                        )
                    }
                }

                database.execSQL("DROP TABLE IF EXISTS verification_log")
                database.execSQL("ALTER TABLE verification_log_new RENAME TO verification_log")
            }
        }
    }
}
