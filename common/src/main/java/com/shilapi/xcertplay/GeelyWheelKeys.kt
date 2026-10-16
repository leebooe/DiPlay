package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.network.GeelyHeadUnit

/** One session owns one listener. The direct input service takes precedence over OneOS. */
internal object GeelyWheelKeys {
    private val modelCode = Regex("(?i)(?:^|[^a-z0-9])(?:g636|fx11|kx11|e245)(?:$|[^a-z0-9])")
    val supportedHeadUnit: Boolean
        get() = GeelyHeadUnit.detected || modelCode.containsMatchIn(Build.MODEL.orEmpty())
    private val handler = Handler(Looper.getMainLooper())
    private var input: GeelyWheelInputConnection? = null
    private var revision = 0L

    @Synchronized
    fun start(context: Context, bluetoothAudio: Boolean, onMedia: (Int) -> Unit, onSiri: () -> Unit) {
        if (!supportedHeadUnit) return
        val expected = ++revision
        handler.post {
            synchronized(this) {
                if (revision != expected) return@post
                input?.close()
                input = GeelyWheelInputConnection(context.applicationContext, bluetoothAudio,
                    onOperation = { operation ->
                        val current = synchronized(this) { revision == expected }
                        if (current) when (operation) {
                            GeelyWheelEventDecoder.Operation.PLAY_PAUSE -> onMedia(CarPlayMediaButton.PLAY_PAUSE)
                            GeelyWheelEventDecoder.Operation.NEXT -> onMedia(CarPlayMediaButton.NEXT)
                            GeelyWheelEventDecoder.Operation.PREVIOUS -> onMedia(CarPlayMediaButton.PREVIOUS)
                            GeelyWheelEventDecoder.Operation.SIRI -> onSiri()
                        }
                    }).also { it.start() }
            }
        }
    }

    @Synchronized
    fun stop() {
        val expected = ++revision
        handler.post {
            synchronized(this) {
                if (revision != expected) return@post
                input?.close()
                input = null
            }
        }
    }
}

/** Binding state and decoded events are confined to the main looper. */
internal class GeelyWheelInputConnection(
    private val context: Context,
    private val bluetoothAudio: Boolean,
    private val onOperation: (GeelyWheelEventDecoder.Operation) -> Unit,
    private val report: (String) -> Unit = { WheelKeyLog.note(it); Log.i("DiPlay-GeelyWheel", it) },
) : AutoCloseable {
    internal enum class Backend(val packageName: String, val className: String) {
        DIRECT("com.geely.inputservice", "com.geely.inputservice.InputService"),
        ONE_OS("com.geely.service.oneosapi", "com.geely.service.oneosapi.OneOSApiService"),
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active: Binding? = null
    private var closed = false

    fun start() {
        if (!closed && active == null) bind(Backend.DIRECT)
    }

    private fun bind(backend: Backend) {
        if (closed) return
        val binding = Binding(backend)
        active = binding
        // Own the binding before a local service or test double can answer synchronously.
        binding.bound = true
        val accepted = runCatching {
            context.bindService(Intent().setComponent(ComponentName(backend.packageName, backend.className)),
                binding, Context.BIND_AUTO_CREATE)
        }.onFailure { report("geely input backend=$backend bind failed=${it.javaClass.simpleName}") }
            .getOrDefault(false)
        if (active !== binding) return
        binding.bound = accepted
        report("geely input backend=$backend bind requested=$accepted")
        if (!accepted) failed(binding, "bind unavailable")
        else handler.postDelayed({
            if (active === binding && !binding.registered) failed(binding, "registration timed out")
        }, RETRY_MILLIS)
    }

    private fun failed(binding: Binding, reason: String) {
        if (closed || active !== binding) return
        report("geely input backend=${binding.backend} $reason")
        active = null
        binding.release()
        val next = if (binding.backend == Backend.DIRECT) Backend.ONE_OS else Backend.DIRECT
        handler.postDelayed({ if (!closed && active == null) bind(next) },
            if (next == Backend.ONE_OS) 0L else RETRY_MILLIS)
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        val binding = active
        active = null
        binding?.release()
    }

    private inner class Binding(val backend: Backend) : ServiceConnection {
        var bound = false
        var registered = false
        private var manager: IInputManager? = null
        private val decoder = GeelyWheelEventDecoder()
        private val listener = object : IInputListener.Stub() {
            override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) =
                receive(GeelyWheelEventDecoder.RAW_KEY, keyCode, event)
            override fun onShortClick(keyCode: Int, softKeyFunction: Int) =
                receive(GeelyWheelEventDecoder.SHORT_CLICK, keyCode)
            override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) =
                receive(GeelyWheelEventDecoder.LONG_PRESS, keyCode)
            override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) {}
            override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) {}
            override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) {}
        }

        private fun receive(transaction: Int, rawCode: Int, action: Int = -1) {
            val code = GeelyWheelKeyCodes.canonicalize(rawCode) ?: return
            val at = SystemClock.elapsedRealtime()
            handler.post {
                if (closed || active !== this || !registered) return@post
                if (bluetoothAudio && code != GeelyWheelKeyCodes.KEY_VOICE_ASSIST) return@post
                val operation = decoder.accept(transaction, rawCode, action, at)
                report("geely input backend=$backend raw=$rawCode key=$code transaction=$transaction action=$action forwarded=${operation != null}")
                operation?.let(onOperation)
            }
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (closed || active !== this) return
            try {
                val binder = if (backend == Backend.ONE_OS) service?.let(::oneOsInputService) else service
                check(binder?.interfaceDescriptor == INPUT_DESCRIPTOR) { "input interface unavailable" }
                val input = IInputManager.Stub.asInterface(binder)
                manager = input
                val codes = if (backend == Backend.DIRECT) GeelyWheelKeyCodes.registeredCodes(bluetoothAudio)
                    else GeelyWheelKeyCodes.oneOsListenerCodes(bluetoothAudio)
                input.registerListener(listener, context.packageName, codes)
                registered = true
                report("geely input backend=$backend registered bluetoothAudio=$bluetoothAudio")
            } catch (error: Exception) {
                failed(this, "registration failed=${error.javaClass.simpleName}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = failed(this, "service disconnected")
        override fun onNullBinding(name: ComponentName?) = failed(this, "null binding")
        override fun onBindingDied(name: ComponentName?) = failed(this, "binding died")

        fun release() {
            runCatching { manager?.unregisterListener(listener, context.packageName) }
            registered = false
            manager = null
            if (bound) runCatching { context.unbindService(this) }
            bound = false
        }
    }

    private fun oneOsInputService(service: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("com.geely.lib.oneosapi.IServiceManager")
            data.writeInt(8) // OneOS input service type.
            check(service.transact(2, data, reply, 0)) { "OneOS getService unavailable" }
            reply.readException()
            reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val RETRY_MILLIS = 3_000L
        const val INPUT_DESCRIPTOR = "com.geely.lib.oneosapi.input.IInputManager"
    }
}
