package koharubiyori.starlight.screen.streaming.util.video

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

class VideoStats {
    var decoderTimeMs: Long = 0
    var totalTimeMs: Long = 0
    var totalFrames: Int = 0
    var totalFramesReceived: Int = 0
    var totalFramesRendered: Int = 0
    var frameLossEvents: Int = 0
    var framesLost: Int = 0
    var minHostProcessingLatency: Char = '\u0000'
    var maxHostProcessingLatency: Char = '\u0000'
    var totalHostProcessingLatency: Int = 0
    var framesWithHostProcessingLatency: Int = 0
    var measurementStartTimestamp: Long = 0

    fun add(other: VideoStats) {
        decoderTimeMs += other.decoderTimeMs
        totalTimeMs += other.totalTimeMs
        totalFrames += other.totalFrames
        totalFramesReceived += other.totalFramesReceived
        totalFramesRendered += other.totalFramesRendered
        frameLossEvents += other.frameLossEvents
        framesLost += other.framesLost

        if (minHostProcessingLatency == '\u0000') {
            minHostProcessingLatency = other.minHostProcessingLatency
        } else {
            minHostProcessingLatency = min(minHostProcessingLatency.code, other.minHostProcessingLatency.code).toChar()
        }
        maxHostProcessingLatency = max(maxHostProcessingLatency.code, other.maxHostProcessingLatency.code).toChar()
        totalHostProcessingLatency += other.totalHostProcessingLatency
        framesWithHostProcessingLatency += other.framesWithHostProcessingLatency

        if (measurementStartTimestamp == 0L) {
            measurementStartTimestamp = other.measurementStartTimestamp
        }

        check(other.measurementStartTimestamp >= measurementStartTimestamp)
    }

    fun copy(other: VideoStats) {
        decoderTimeMs = other.decoderTimeMs
        totalTimeMs = other.totalTimeMs
        totalFrames = other.totalFrames
        totalFramesReceived = other.totalFramesReceived
        totalFramesRendered = other.totalFramesRendered
        frameLossEvents = other.frameLossEvents
        framesLost = other.framesLost
        minHostProcessingLatency = other.minHostProcessingLatency
        maxHostProcessingLatency = other.maxHostProcessingLatency
        totalHostProcessingLatency = other.totalHostProcessingLatency
        framesWithHostProcessingLatency = other.framesWithHostProcessingLatency
        measurementStartTimestamp = other.measurementStartTimestamp
    }

    fun clear() {
        decoderTimeMs = 0
        totalTimeMs = 0
        totalFrames = 0
        totalFramesReceived = 0
        totalFramesRendered = 0
        frameLossEvents = 0
        framesLost = 0
        minHostProcessingLatency = '\u0000'
        maxHostProcessingLatency = '\u0000'
        totalHostProcessingLatency = 0
        framesWithHostProcessingLatency = 0
        measurementStartTimestamp = 0
    }

    fun getFps(): VideoStatsFps {
        val elapsed = (SystemClock.uptimeMillis() - measurementStartTimestamp) / 1000.0f

        val fps = VideoStatsFps()
        if (elapsed > 0) {
            fps.totalFps = totalFrames / elapsed
            fps.receivedFps = totalFramesReceived / elapsed
            fps.renderedFps = totalFramesRendered / elapsed
        }
        return fps
    }
}

class VideoStatsFps {
    var totalFps: Float = 0f
    var receivedFps: Float = 0f
    var renderedFps: Float = 0f
}

