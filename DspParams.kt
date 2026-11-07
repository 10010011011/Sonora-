package app.sonora.player.audio

import app.sonora.player.data.Settings
import app.sonora.player.data.TrackAudioConfig
import app.sonora.player.data.effectiveSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

class DspParamsHolder {
    @Volatile
    var p: DspParams = DspParams()

    /** The live processor (set by the renderers factory) so the UI can read the visualizer tap and limiter meter. */
    @Volatile
    var processor: SonoraAudioProcessor? = null
}

/** Immutable snapshot read by the audio thread. Mirrors engine.ts apply() (see PLAN > DSP spec). */
class DspParams(
    val preampLin: Double = 1.0,
    val rgLin: Double = 1.0,
    val eqGainsDb: DoubleArray = DoubleArray(10),
    val bassDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val spatial: Boolean = false,
    val mLL: Double = 1.0,
    val mRL: Double = 0.0,
    val mLR: Double = 0.0,
    val mRR: Double = 1.0,
    val crossfeedGain: Double = 0.0,
    val orbit: Boolean = false,
    val orbitSpeed: Double = 0.12,
    val orbitDry: Double = 1.0,
    val orbitWet: Double = 0.0,
    val room: Double = 0.0,
    val roomDry: Double = 1.0,
    val roomWet: Double = 0.0,
    val boostLin: Double = 1.0,
    val limiter: Boolean = false,
) {
    companion object {
        fun dbToLin(db: Double): Double = 10.0.pow(db / 20.0)

        /** rgDb = ReplayGain (+ rgPreamp) in dB for the current track, 0 when off. */
        fun from(rawSettings: Settings, rgDb: Double = 0.0, trackCfg: TrackAudioConfig? = null): DspParams {
            val s = effectiveSettings(rawSettings, trackCfg)
            val eq = DoubleArray(10) { i -> if (s.eqOn) s.eq.getOrElse(i) { 0.0 } else 0.0 }
            val bass = if (s.eqOn) s.bassDb else 0.0
            val treble = if (s.eqOn) s.trebleDb else 0.0
            val maxBoost = if (s.eqOn) max(0.0, max(eq.maxOrNull() ?: 0.0, max(bass, treble))) else 0.0
            val preampDb = s.preampDb - (if (s.autoPreamp) maxBoost else 0.0)

            val wantSpatial = s.stereoMode != "normal" || abs(s.width - 1.0) > 0.005 || s.crossfeed > 0.005
            var ll = 1.0
            var rl = 0.0
            var lr = 0.0
            var rr = 1.0
            if (wantSpatial) {
                if (s.stereoMode == "karaoke") {
                    ll = 0.7; rl = -0.7; lr = 0.7; rr = -0.7
                } else {
                    val m = 1.0
                    val sd = when (s.stereoMode) {
                        "mono" -> 0.0
                        "vocal" -> 0.25
                        else -> s.width
                    }
                    ll = (m + sd) / 2.0; rr = ll
                    rl = (m - sd) / 2.0; lr = rl
                }
            }
            val netGainDb = s.boostDb + preampDb + maxBoost + max(0.0, rgDb)
            val widens = wantSpatial && s.width > 1.005
            val wantLimiter = s.limiter && (netGainDb > 0.05 || widens || s.orbit || s.room > 0.01)
            val roomClamped = s.room.coerceIn(0.0, 1.0)
            return DspParams(
                preampLin = dbToLin(preampDb),
                rgLin = dbToLin(rgDb),
                eqGainsDb = eq,
                bassDb = bass,
                trebleDb = treble,
                spatial = wantSpatial,
                mLL = ll,
                mRL = rl,
                mLR = lr,
                mRR = rr,
                crossfeedGain = s.crossfeed * 0.45,
                orbit = s.orbit,
                orbitSpeed = s.orbitSpeed.coerceIn(0.03, 0.6),
                orbitDry = if (s.orbit) 0.2 else 1.0,
                orbitWet = if (s.orbit) 1.0 else 0.0,
                room = roomClamped,
                roomDry = if (roomClamped > 0.001) 1.0 - roomClamped * 0.3 else 1.0,
                roomWet = if (roomClamped > 0.001) roomClamped * 0.55 else 0.0,
                boostLin = dbToLin(s.boostDb),
                limiter = wantLimiter,
            )
        }
    }
}
