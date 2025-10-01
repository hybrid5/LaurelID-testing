package com.laurelid.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VerificationDaoTest {
    private lateinit var context: Context
    private lateinit var db: AppDb
    private lateinit var dao: VerificationDao

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.verificationDao()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun schemaOmitsPiiColumns() {
        val cursor = db.openHelper.readableDatabase.query("PRAGMA table_info(verification_log)")
        val columns = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val nameIndex = it.getColumnIndex("name")
                if (nameIndex >= 0) {
                    columns += it.getString(nameIndex)
                }
            }
        }

        assertFalse(columns.contains("subjectDid"))
        assertFalse(columns.contains("docType"))
        assertFalse(columns.contains("issuer"))
        assertTrue(columns.contains("totalSuccessCount"))
        assertTrue(columns.contains("totalDemoModeCount"))
    }

    @Test
    fun aggregateCountsIncrement() = runBlocking {
        val first = VerificationEntity(
            success = true,
            ageOver21 = true,
            demoMode = false,
            error = null,
            tsMillis = 1L,
            totalSuccessCount = 1,
            totalFailureCount = 0,
            totalAgeOver21Count = 1,
            totalAgeUnder21Count = 0,
            totalDemoModeCount = 0,
        )
        dao.insert(first)

        val second = VerificationEntity(
            success = false,
            ageOver21 = false,
            demoMode = true,
            error = "fail",
            tsMillis = 2L,
            totalSuccessCount = 1,
            totalFailureCount = 1,
            totalAgeOver21Count = 1,
            totalAgeUnder21Count = 1,
            totalDemoModeCount = 1,
        )
        dao.insert(second)

        val latest = dao.mostRecent()
        requireNotNull(latest)
        assertEquals(1, latest.totalSuccessCount)
        assertEquals(1, latest.totalFailureCount)
        assertEquals(1, latest.totalAgeOver21Count)
        assertEquals(1, latest.totalAgeUnder21Count)
        assertEquals(1, latest.totalDemoModeCount)
        assertTrue(latest.demoMode)
    }
}
