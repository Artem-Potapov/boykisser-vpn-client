package com.justme.xtls_core_proxy.failover

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.db.Subscription
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailoverPoolResolverTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun subscriptionProfile_resolvesToItsSubscriptionSiblings() = runBlocking {
        val subDao = db.subscriptionDao()
        val dao = db.profileDao()
        val subA = subDao.insert(Subscription(name = "A", url = "https://a.test/sub"))
        val subB = subDao.insert(Subscription(name = "B", url = "https://b.test/sub"))
        val a1 = dao.insert(Profile(name = "a1", config = "{}", subscriptionId = subA))
        val a2 = dao.insert(Profile(name = "a2", config = "{}", subscriptionId = subA))
        dao.insert(Profile(name = "b1", config = "{}", subscriptionId = subB))
        dao.insert(Profile(name = "manual", config = "{}", subscriptionId = null))

        val current = dao.getById(a1)!!
        val pool = FailoverPoolResolver.resolve(dao, current)

        assertEquals(listOf(a1, a2), pool.map { it.id })
    }

    @Test
    fun manualProfile_resolvesToTheManualSet() = runBlocking {
        val subDao = db.subscriptionDao()
        val dao = db.profileDao()
        val sub = subDao.insert(Subscription(name = "A", url = "https://a.test/sub"))
        dao.insert(Profile(name = "sub1", config = "{}", subscriptionId = sub))
        val m1 = dao.insert(Profile(name = "m1", config = "{}", subscriptionId = null))
        val m2 = dao.insert(Profile(name = "m2", config = "{}", subscriptionId = null))

        val current = dao.getById(m1)!!
        val pool = FailoverPoolResolver.resolve(dao, current)

        assertEquals(listOf(m1, m2), pool.map { it.id })
    }
}
