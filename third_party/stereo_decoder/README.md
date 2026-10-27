# Optional stereo audio decoder

Based on AndroidX Media3 1.4.1 decoder_ffmpeg (Apache 2.0), with an audio-only FFmpeg 6.1 maintenance snapshot (LGPL 2.1 or later). Exact immutable commits and source archive SHA-256 are in sources.json. License texts are included here.

Pink TV modifications: library name pinkstereo; output always PCM 16-bit, two channels; libswresample downmix includes the centre and surround channels; output sample count uses actual swr_convert return value; initialise TrueHD input layouts and preserve them during a seek (https://github.com/androidx/media/issues/1843); PCM input formats supported. Used only when this TV chooses Stéréo. Other modes retain the original renderer.

Rebuild on Linux with Android NDK 28.2.13676358: ANDROID_HOME points to the SDK, then bash tools/build_stereo_decoder.sh. Four ABIs: armeabi-v7a, arm64-v8a, x86, x86_64. The output shared objects are replaceable in app/src/main/jniLibs before rebuilding/re-signing the APK. Sources for the modified wrapper are in this directory and app/src/main/java/androidx/media3/decoder/ffmpeg. The matching FFmpeg source archive is also provided with releases that include these shared objects.

Only audio decoders and swresample/avutil are compiled; no network protocol, demuxer, video decoder, encoder, GPL, nonfree or external codec library is enabled. The application obtains streams directly from the user’s configured provider; the decoder processes audio locally and makes no network requests.
