package com.tuneai.audio.nativebridge

class NativeAudioEngine {

    companion object {
        init {
            System.loadLibrary("audioengine")
        }

        const val PROFILE_STUDIO_FLAT: Int = 1
        const val PROFILE_VOCAL_PRESENCE: Int = 2
        const val PROFILE_DYNAMIC_PUNCH: Int = 3
    }

    fun initEngine(): Boolean = nativeInitEngine()

    fun play(filePath: String): Boolean = nativePlay(filePath)

    fun pause() = nativePause()

    fun setProfile(profileId: Int) = nativeSetProfile(profileId)

    fun getFftData(): FloatArray = nativeGetFftData()

    private external fun nativeInitEngine(): Boolean
    private external fun nativePlay(filePath: String): Boolean
    private external fun nativePause()
    private external fun nativeSetProfile(profileId: Int)
    private external fun nativeGetFftData(): FloatArray
}
