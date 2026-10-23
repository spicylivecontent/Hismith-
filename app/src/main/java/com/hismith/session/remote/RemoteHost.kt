package com.hismith.session.remote

import android.os.Handler
import android.os.SystemClock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The phone's side of the one-time remote link. Connects to the relay server as "host" over
 * WSS, receives a one-time invite token and relays controller messages. The phone stays the
 * authority: the server never touches Bluetooth and everything it forwards is validated here.
 *
 * All callbacks are posted to [handler] (the service's control thread).
 */
class RemoteHost(private val handler: Handler, private val listener: Listener) {

    interface Listener {
        fun onRemoteChanged()
        fun onControllerJoined(resumed: Boolean)
        fun onControllerLeft()
        fun onControllerMessage(m: JSONObject)
        /** The host connection itself dropped (counts as losing the controller). */
        fun onHostLinkLost()
    }

    enum class Status(val label: String) {
        OFF("Av"),
        CONNECTING("Ansluter till servern… (en gratisserver kan behöva upp till en minut för att vakna)"),
        READY("Länken är redo – väntar på mottagaren"),
        PENDING("Mottagaren väntar på ditt godkännande"),
        ACTIVE("Mottagaren är ansluten"),
        RECONNECTING("Återansluter till servern…"),
        ERROR("Fel"),
    }

    companion object {
        private const val HOST_HEARTBEAT_MS = 2_000L
        private const val MAX_RECONNECT_MS = 60_000L

        /**
         * Normalises what the user typed: `relay.example.com` → `https://relay.example.com`.
         * Only HTTPS is accepted – the link must run over TLS.
         */
        fun normalize(url: String): String? {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "https://$u"
            if (!u.startsWith("https://") || u.length <= "https://".length) return null
            return u
        }

        /** Free hosts sleep when idle; the first request can take up to a minute. */
        private val wakeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(100, TimeUnit.SECONDS)
                .build()
        }

        data class ProbeResult(
            val ok: Boolean,
            val ms: Long,
            val secretRequired: Boolean? = null,
            val version: String? = null,
            val error: String? = null,
        )

        /** Checks `<server>/api/info`. [onResult] runs on a background thread. */
        fun probe(serverUrl: String, onResult: (ProbeResult) -> Unit) {
            val base = normalize(serverUrl) ?: return onResult(ProbeResult(false, 0, error = "Adressen måste börja med https://"))
            val started = SystemClock.elapsedRealtime()
            wakeClient.newCall(Request.Builder().url("$base/api/info").build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onResult(ProbeResult(false, SystemClock.elapsedRealtime() - started, error = e.message ?: "nätverksfel"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val ms = SystemClock.elapsedRealtime() - started
                        val body = try { r.body?.string() } catch (_: IOException) { null }
                        val info = try { body?.let { JSONObject(it) } } catch (_: Exception) { null }
                        when {
                            !r.isSuccessful -> onResult(ProbeResult(false, ms, error = "HTTP ${r.code} – är det rätt adress?"))
                            info?.optString("name") != "hismith-relay" -> onResult(ProbeResult(false, ms, error = "Svarar, men det är ingen Hismith-relay"))
                            else -> onResult(ProbeResult(true, ms, info.optBoolean("secretRequired"), info.optString("version")))
                        }
                    }
                }
            })
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    var status = Status.OFF
        private set
    var errorMessage: String? = null
        private set
    /** The shareable one-time link, or null when none is open. */
    var link: String? = null
        private set
    var inviteExpiresAtMs = 0L
        private set
    var controllerPresent = false
        private set

    private var baseUrl = ""
    private var secret = ""
    private var socket: WebSocket? = null
    private var generation = 0
    private var hostResume: String? = null
    private var lastHeartbeat = 0L
    private var lostAt = 0L
    private var reconnectDelay = 1_000L
    private var reconnectAt = 0L
    private var approved = false

    val isOn: Boolean get() = status != Status.OFF && status != Status.ERROR

    fun start(serverUrl: String, hostSecret: String) {
        val base = normalize(serverUrl)
        if (base == null) {
            fail("Serveradressen måste börja med https://")
            return
        }
        stopSocket()
        baseUrl = base
        secret = hostSecret
        hostResume = null
        link = null
        controllerPresent = false
        approved = false
        errorMessage = null
        reconnectDelay = 1_000L
        status = Status.CONNECTING
        listener.onRemoteChanged()
        // Wake a sleeping free server first, then open the WebSocket (also if the wake-up fails).
        val gen = generation
        wakeClient.newCall(Request.Builder().url("$baseUrl/healthz").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { if (gen == generation && status == Status.CONNECTING) open() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                handler.post { if (gen == generation && status == Status.CONNECTING) open() }
            }
        })
    }

    private fun open() {
        val gen = ++generation
        val wsUrl = "wss://" + baseUrl.removePrefix("https://") + "/ws/host"
        val req = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (gen != generation) return@post
                    val hello = JSONObject().put("t", "hello").put("v", 1).put("secret", secret)
                    hostResume?.let { hello.put("resume", it) }
                    webSocket.send(hello.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { if (gen == generation) onServerMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { if (gen == generation) onDropped(if (code == 1000) null else reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { if (gen == generation) onDropped(t.message ?: "nätverksfel") }
            }
        })
    }

    private fun onServerMessage(text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        when (m.optString("t")) {
            "ready" -> {
                hostResume = m.optString("hostResume").ifEmpty { hostResume }
                reconnectDelay = 1_000L
                if (m.has("invite") && !m.isNull("invite")) setInvite(m.getString("invite"), m.optLong("inviteTtlSec", 900))
                else if (!m.optBoolean("inviteValid", false)) link = null
                controllerPresent = m.optString("controller") == "active" || m.optString("controller") == "pending"
                if (m.optString("controller") == "active" && !approved) {
                    // The phone revoked this controller while it was offline: tell the server now.
                    socket?.send("""{"t":"kick"}""")
                    controllerPresent = false
                }
                status = when {
                    controllerPresent && approved -> Status.ACTIVE
                    controllerPresent -> Status.PENDING
                    else -> Status.READY
                }
                listener.onRemoteChanged()
            }
            "invite" -> {
                setInvite(m.getString("invite"), m.optLong("inviteTtlSec", 900))
                listener.onRemoteChanged()
            }
            "ctl_joined" -> {
                controllerPresent = true
                link = null // the invite is used up
                val resumed = m.optBoolean("resumed", false)
                if (!resumed) approved = false
                status = if (approved) Status.ACTIVE else Status.PENDING
                listener.onControllerJoined(resumed && approved)
                listener.onRemoteChanged()
            }
            "ctl" -> m.optJSONObject("m")?.let { listener.onControllerMessage(it) }
            "ctl_left" -> {
                controllerPresent = false
                if (status == Status.ACTIVE || status == Status.PENDING) status = Status.READY
                listener.onControllerLeft()
                listener.onRemoteChanged()
            }
            "error" -> {
                val code = m.optString("code")
                when (code) {
                    "bad_secret" -> fail("Servern nekade: fel värdhemlighet")
                    "session_gone" -> {
                        // Resume window passed: start a brand-new session.
                        hostResume = null
                        link = null
                        socket?.send(JSONObject().put("t", "hello").put("v", 1).put("secret", secret).toString())
                    }
                    else -> { errorMessage = m.optString("msg", code); listener.onRemoteChanged() }
                }
            }
        }
    }

    private fun setInvite(token: String, ttlSec: Long) {
        link = "$baseUrl/r/#$token"
        inviteExpiresAtMs = SystemClock.elapsedRealtime() + ttlSec * 1000
    }

    private fun onDropped(reason: String?) {
        socket = null
        if (status == Status.OFF || status == Status.ERROR) return
        val hadController = controllerPresent && approved
        status = Status.RECONNECTING
        errorMessage = reason
        lostAt = SystemClock.elapsedRealtime().let { if (lostAt == 0L) it else lostAt }
        reconnectAt = SystemClock.elapsedRealtime() + reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(8_000)
        if (hadController) listener.onHostLinkLost()
        listener.onRemoteChanged()
    }

    private fun fail(msg: String) {
        stopSocket()
        status = Status.ERROR
        errorMessage = msg
        link = null
        controllerPresent = false
        listener.onRemoteChanged()
    }

    /** Called from the control loop. */
    fun tick(now: Long) {
        when (status) {
            Status.RECONNECTING -> if (socket == null && now >= reconnectAt) {
                if (lostAt > 0 && now - lostAt > MAX_RECONNECT_MS) {
                    fail("Tappade servern i över en minut – starta länken igen")
                } else {
                    open()
                }
            }
            Status.READY, Status.PENDING, Status.ACTIVE -> {
                lostAt = 0
                if (now - lastHeartbeat >= HOST_HEARTBEAT_MS) {
                    lastHeartbeat = now
                    socket?.send("""{"t":"hb"}""")
                }
                if (link != null && inviteExpiresAtMs in 1 until now) link = null
            }
            else -> {}
        }
    }

    fun newInvite() {
        socket?.send("""{"t":"new_invite"}""")
    }

    fun approve() {
        approved = true
        socket?.send("""{"t":"approve"}""")
        if (controllerPresent) status = Status.ACTIVE
        listener.onRemoteChanged()
    }

    fun deny() {
        approved = false
        socket?.send("""{"t":"deny"}""")
        controllerPresent = false
        status = Status.READY
        listener.onRemoteChanged()
    }

    /** Throw the controller out; it cannot come back without a new link. */
    fun kick() {
        approved = false
        controllerPresent = false
        socket?.send("""{"t":"kick"}""")
        if (status == Status.ACTIVE || status == Status.PENDING) status = Status.READY
        listener.onRemoteChanged()
    }

    val isControllerActive: Boolean get() = controllerPresent && approved && status == Status.ACTIVE

    fun sendState(s: JSONObject) {
        if (!controllerPresent) return
        socket?.send(JSONObject().put("t", "state").put("s", s).toString())
    }

    fun sendNotice(text: String) {
        if (!controllerPresent) return
        socket?.send(JSONObject().put("t", "notice").put("text", text).toString())
    }

    /** End the remote session completely (all tokens die on the server). */
    fun stop() {
        socket?.send("""{"t":"end"}""")
        stopSocket()
        status = Status.OFF
        link = null
        controllerPresent = false
        approved = false
        hostResume = null
        errorMessage = null
        listener.onRemoteChanged()
    }

    private fun stopSocket() {
        generation++
        socket?.close(1000, "bye")
        socket = null
    }
}
