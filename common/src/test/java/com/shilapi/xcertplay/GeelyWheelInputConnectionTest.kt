package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GeelyWheelInputConnectionTest {
    private val context = InputContext()
    private val operations = mutableListOf<GeelyWheelEventDecoder.Operation>()
    private val messages = mutableListOf<String>()
    private val connections = mutableListOf<GeelyWheelInputConnection>()

    @After fun cleanup() {
        connections.forEach(GeelyWheelInputConnection::close)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun directServiceHasPriorityAndRemainsTheOnlyRegisteredBackend() {
        start()
        val input = InputManager()
        context.connect(input)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.SECONDS)
        assertEquals(listOf("com.geely.inputservice"), context.requested)
        input.listener!!.onKeyCodeEvent(87, 0, 0)
        input.listener!!.onKeyCodeEvent(200087, 1, 0)
        input.listener!!.onShortClick(87, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(GeelyWheelEventDecoder.Operation.NEXT), operations)
    }

    @Test fun failedDirectBindingFallsBackToOneOsServiceManager() {
        context.acceptDirect = false
        start()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("com.geely.inputservice", "com.geely.service.oneosapi"), context.requested)
        val input = InputManager()
        context.connect(OneOsService(input))
        input.listener!!.onShortClick(85, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(GeelyWheelEventDecoder.Operation.PLAY_PAUSE), operations)
    }

    @Test fun pendingDirectBindingIsReleasedBeforeFallbackAndLateCallbacksAreIgnored() {
        start()
        val oldBinding = context.bindings.single()
        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)
        assertTrue(context.released.contains(oldBinding.second))
        val stale = InputManager()
        oldBinding.second.onServiceConnected(oldBinding.first, stale)
        assertNull(stale.listener)
        val input = InputManager()
        context.connect(OneOsService(input))
        assertNotNull(input.listener)
    }

    @Test fun registrationFailureReleasesPartialRegistrationBeforeTryingOneOs() {
        start()
        val input = InputManager(failRegistration = true)
        context.connect(input)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, input.unregistered)
        assertEquals("com.geely.service.oneosapi", context.requested.last())
    }

    @Test fun closeDiscardsQueuedKeysAndPreventsRetries() {
        val connection = start()
        val input = InputManager()
        context.connect(input)
        input.listener!!.onShortClick(88, 0)
        connection.close()
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertTrue(operations.isEmpty())
        assertEquals(1, input.unregistered)
        assertEquals(1, context.requested.size)
    }

    @Test fun bluetoothModeRegistersVoiceAndKeepsLongPressSiri() {
        start(bluetooth = true)
        val input = InputManager()
        context.connect(input)
        assertEquals(listOf(200231), input.codes.toList())
        input.listener!!.onShortClick(85, 0) // Guard against a vendor ignoring the subscription filter.
        input.listener!!.onShortClick(231, 0)
        input.listener!!.onLongPressTriggered(231, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(GeelyWheelEventDecoder.Operation.SIRI), operations)
    }

    @Test fun bluetoothOneOsFallbackSubscribesOnlyToVoiceAliases() {
        context.acceptDirect = false
        start(bluetooth = true)
        shadowOf(Looper.getMainLooper()).idle()
        val input = InputManager()
        context.connect(OneOsService(input))
        assertEquals(listOf(200231, 231), input.codes.toList())
    }

    @Test fun closingBetweenFailedBackendsCancelsTheNextRetry() {
        context.acceptDirect = false
        context.acceptOneOs = false
        val connection = start()
        shadowOf(Looper.getMainLooper()).idle()
        connection.close()
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(listOf("com.geely.inputservice", "com.geely.service.oneosapi"), context.requested)
    }

    private fun start(bluetooth: Boolean = false) =
        GeelyWheelInputConnection(context, bluetooth, operations::add, messages::add)
            .also { connections += it; it.start() }

    private class InputContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var acceptDirect = true
        var acceptOneOs = true
        val requested = mutableListOf<String>()
        val bindings = mutableListOf<Pair<ComponentName, ServiceConnection>>()
        val released = mutableListOf<ServiceConnection>()

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val component = requireNotNull(intent.component)
            requested += component.packageName
            if (!acceptDirect && component.packageName == "com.geely.inputservice") return false
            if (!acceptOneOs && component.packageName == "com.geely.service.oneosapi") return false
            bindings += component to connection
            return true
        }

        override fun unbindService(connection: ServiceConnection) { released += connection }

        fun connect(binder: IBinder) {
            val (component, connection) = bindings.last()
            connection.onServiceConnected(component, binder)
        }
    }

    private class InputManager(private val failRegistration: Boolean = false) : IInputManager.Stub() {
        var listener: IInputListener? = null
        var codes = intArrayOf()
        var unregistered = 0
        override fun interceptKeyCode(keyCode: Int, packageName: String): Boolean = false
        override fun releaseKeyCode(keyCode: Int, packageName: String): Boolean = false
        override fun getControlIndex(): Int = 0
        override fun registerListener(listener: IInputListener, packageName: String, keyCodes: IntArray) {
            this.listener = listener
            codes = keyCodes
            if (failRegistration) throw IllegalStateException("vendor registration failed")
        }
        override fun unregisterListener(listener: IInputListener, packageName: String) { unregistered++ }
    }

    private class OneOsService(private val input: IBinder) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 2) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("com.geely.lib.oneosapi.IServiceManager")
            assertEquals(8, data.readInt())
            val response = requireNotNull(reply)
            response.writeNoException()
            response.writeStrongBinder(input)
            return true
        }
    }
}
