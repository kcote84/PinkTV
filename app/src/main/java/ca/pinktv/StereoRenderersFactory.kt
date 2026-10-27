package ca.pinktv

import android.content.Context
import android.os.Handler
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Stereo is an output guarantee, not a limit on the encoded input channels.
 * Keep Media3's video, text, seeking and data source unchanged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StereoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>
    ) {
        // This build of the optional decoder always emits two-channel PCM.
        // Do not silently fall back to multichannel passthrough in Stereo mode.
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
