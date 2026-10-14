package com.justme.xtls_core_proxy.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileDaoManualQueryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun flowAndSnapshotReturnSameOrderedManualOnlyRows() = runBlocking {
        val subscriptionId = db.subscriptionDao().insert(
            Subscription(name = "Sub", url = "https://sub.test/list")
        )
        val dao = db.profileDao()
        val manual1 = dao.insert(Profile(name = "Manual 1", config = "{}"))
        val subscription = dao.insert(
            Profile(name = "Subscription", config = "{}", subscriptionId = subscriptionId)
        )
        val manual2 = dao.insert(Profile(name = "Manual 2", config = "{}"))

        val flowRows = dao.getManual().first()
        val snapshotRows = dao.getManualList()

        assertEquals(listOf(manual1, manual2), flowRows.map { it.id })
        assertEquals(flowRows, snapshotRows)
        assertEquals(listOf(manual1, manual2), snapshotRows.map { it.id })
        assertFalse(snapshotRows.any { it.id == subscription })
    }
}
