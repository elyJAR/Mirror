package com.antigravity.mirror.stream.selector

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.antigravity.mirror.stream.api.Transport
import com.antigravity.mirror.stream.transport.TransportId
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TransportSelectorTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        testDataStore = PreferenceDataStoreFactory.create(
            produceFile = { tmpFolder.newFile("test.preferences_pb") }
        )
    }

    @Test
    fun `selectTransports returns only LAN when preference is LAN`() = runTest {
        val selector = TransportSelector(context, testDataStore)
        val result = selector.selectTransports(Transport.LAN)
        result.shouldContainExactly(TransportId.LAN)
    }

    @Test
    fun `selectTransports returns only MIRACAST when preference is MIRACAST`() = runTest {
        val selector = TransportSelector(context, testDataStore)
        val result = selector.selectTransports(Transport.MIRACAST)
        result.shouldContainExactly(TransportId.MIRACAST)
    }

    @Test
    fun `selectTransports returns MIRACAST then LAN for unknown Pixel device`() = runTest {
        val selector = TransportSelector(
            context, testDataStore, 
            manufacturer = "Google", 
            sdkInt = 33
        )
        val result = selector.selectTransports(Transport.AUTO)
        result.shouldContainExactly(TransportId.MIRACAST, TransportId.LAN)
    }

    @Test
    fun `selectTransports returns only LAN for unknown Samsung device`() = runTest {
        val selector = TransportSelector(
            context, testDataStore, 
            manufacturer = "Samsung", 
            sdkInt = 34
        )
        val result = selector.selectTransports(Transport.AUTO)
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
        selector.selectTransports(Transport.AUTO).shouldContainExactly(TransportId.MIRACAST, TransportId.LAN)

        // Record failure
        selector.recordOutcome(TransportId.MIRACAST, false)

        // Now should be DENIED
        selector.selectTransports(Transport.AUTO).shouldContainExactly(TransportId.LAN)
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
        selector.selectTransports(Transport.AUTO).shouldContainExactly(TransportId.LAN)

        // Record success (e.g. if forced or somehow worked)
        selector.recordOutcome(TransportId.MIRACAST, true)

        // Now should be ALLOWED
        selector.selectTransports(Transport.AUTO).shouldContainExactly(TransportId.MIRACAST, TransportId.LAN)
    }
}
