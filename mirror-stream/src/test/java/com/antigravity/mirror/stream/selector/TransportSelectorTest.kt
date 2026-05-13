package com.antigravity.mirror.stream.selector

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.antigravity.mirror.stream.api.TransportPreference
import com.antigravity.mirror.stream.transport.TransportId
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TransportSelectorTest {

    @get:Rule
    val timeout = Timeout(10, TimeUnit.SECONDS)

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var context: Context

    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + Job())
        testDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tmpFolder.newFile("test.preferences_pb") }
        )
    }

    @After
    fun teardown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `selectTransports returns only LAN when preference is LAN`() = runTest {
        val selector = TransportSelector(context, testDataStore)
        val result = selector.selectTransports(TransportPreference.LAN)
        result.shouldContainExactly(TransportId.LAN)
    }

    @Test
    fun `selectTransports returns only MIRACAST when preference is MIRACAST`() = runTest {
        val selector = TransportSelector(context, testDataStore)
        val result = selector.selectTransports(TransportPreference.MIRACAST)
        result.shouldContainExactly(TransportId.MIRACAST)
    }

    @Test
    fun `selectTransports returns MIRACAST then LAN for unknown Pixel device`() = runTest {
        val selector = TransportSelector(
            context, testDataStore, 
            manufacturer = "Google", 
            sdkInt = 33
        )
        val result = selector.selectTransports(TransportPreference.AUTO)
        result.shouldContainExactly(TransportId.MIRACAST, TransportId.LAN)
    }

    @Test
    fun `selectTransports returns only LAN for unknown Samsung device`() = runTest {
        val selector = TransportSelector(
            context, testDataStore, 
            manufacturer = "Samsung", 
            sdkInt = 34
        )
        val result = selector.selectTransports(TransportPreference.AUTO)
        result.shouldContainExactly(TransportId.LAN)
    }

    @Test
    fun `recordOutcome demotes Miracast to DENIED on failure`() = runTest {
        val selector = TransportSelector(
            context, testDataStore, 
            manufacturer = "Google", 
            sdkInt = 33
        )
        
        // Initially allowed via heuristic
        selector.selectTransports(TransportPreference.AUTO).shouldContainExactly(TransportId.MIRACAST, TransportId.LAN)

        // Record failure
        selector.recordOutcome(TransportId.MIRACAST, false)

        // Now should be DENIED
        selector.selectTransports(TransportPreference.AUTO).shouldContainExactly(TransportId.LAN)
    }
    
    @Test
    fun `recordOutcome promotes Miracast to ALLOWED on success`() = runTest {
        // Device where heuristic would deny (e.g. Samsung)
        val selector = TransportSelector(
            context, testDataStore, 
            manufacturer = "Samsung", 
            sdkInt = 34
        )
        
        // Initially denied
        selector.selectTransports(TransportPreference.AUTO).shouldContainExactly(TransportId.LAN)

        // Record success (e.g. if forced or somehow worked)
        selector.recordOutcome(TransportId.MIRACAST, true)

        // Now should be ALLOWED
        selector.selectTransports(TransportPreference.AUTO).shouldContainExactly(TransportId.MIRACAST, TransportId.LAN)
    }
}
