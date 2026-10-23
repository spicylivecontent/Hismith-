package com.hismith.session

import com.hismith.session.core.ActiveDoseInfo
import com.hismith.session.core.CommandArbiter
import com.hismith.session.core.DoseKind
import com.hismith.session.core.SessionMode
import com.hismith.session.core.SessionRecord
import com.hismith.session.core.SessionStats
import com.hismith.session.core.SessionStyle
import com.hismith.session.core.Source
import com.hismith.session.remote.RemoteHost

data class DeviceUi(
    val id: String,
    val name: String,
    val role: DeviceRole,
    val kind: DeviceKind,
    val connected: Boolean,
    val detail: String,
)

data class SessionUi(
    val running: Boolean,
    val paused: Boolean,
    val label: String,
    val elapsedSec: Long,
    val remainingSec: Long?,
    val progress: Float,
    val intensity: Int,
    val finalMl: Double,
    val finalStarted: Boolean,
    val challengeDay: Int?,
    val style: SessionStyle,
)

data class LubeUi(
    val connected: Boolean,
    val name: String?,
    val role: DeviceRole?,
    val calibrated: Boolean,
    val supportsReverse: Boolean,
    val active: ActiveDoseInfo?,
    val queued: List<DoseKind>,
    val sessionMl: Double,
    val reservoirMl: Double?,
    val firmware: String?,
    val linkAgeMs: Long?,
)

data class RemoteUi(
    val status: RemoteHost.Status,
    val link: String?,
    val error: String?,
    val pendingApproval: Boolean,
    val controllerActive: Boolean,
    val controllerSilent: Boolean,
)

/** Immutable snapshot of everything the UI shows, published ~10 times per second. */
data class UiState(
    val devices: List<DeviceUi>,
    val mode: SessionMode,
    val control: CommandArbiter.Control,
    val owner: Source?,
    val safety: CommandArbiter.Safety,
    val safetyReason: String,
    val speed: Int,
    val manualTarget: Int,
    val session: SessionUi,
    val lube: LubeUi,
    val remote: RemoteUi,
    val events: List<String>,
    val speedHistory: IntArray,
    val stats: SessionStats,
    /** Latest sessions first. */
    val recent: List<SessionRecord>,
) {
    val anyMotionConnected: Boolean get() = devices.any { it.role.isMotion && it.connected }

    /** Show STOP whenever anything can move or pump. */
    val stopRelevant: Boolean
        get() = control != CommandArbiter.Control.NONE || lube.active != null || speed > 0 ||
            remote.controllerActive

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
