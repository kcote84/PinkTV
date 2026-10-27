package ca.pinktv
object AudioCompatibility {
    fun localStereo(mode: String, decoderAvailable: Boolean) = mode == "stereo" && decoderAvailable
    fun streamUrl(url: String, kind: String, compatible: Boolean): String = url
}
