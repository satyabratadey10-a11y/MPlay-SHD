#pragma once

#include <jni.h>
#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <complex>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

class AudioPlayer final : public oboe::AudioStreamDataCallback,
                          public oboe::AudioStreamErrorCallback {
public:
    enum class Profile : int {
        StudioFlat = 1,
        VocalPresence = 2,
        DynamicPunch = 3,
    };

    AudioPlayer();
    ~AudioPlayer() override;

    bool initEngine();
    bool playFile(const std::string &filePath);
    void pause();
    void setProfile(Profile profile);
    std::vector<float> getFftDataSnapshot() const;

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData,
                                          int32_t numFrames) override;
    bool onError(oboe::AudioStream *audioStream,
                 oboe::Result error) override;

private:
    struct Biquad {
        float b0 = 1.0f;
        float b1 = 0.0f;
        float b2 = 0.0f;
        float a1 = 0.0f;
        float a2 = 0.0f;
        float z1 = 0.0f;
        float z2 = 0.0f;

        void reset();
        void setupPeaking(float sampleRate, float centerHz, float q, float gainDb);
        void setupLowShelf(float sampleRate, float centerHz, float slope, float gainDb);
        float process(float x);
    };

    static constexpr int kOutputChannels = 2;
    static constexpr int kFftSize = 1024;
    static constexpr int kFftBins = 128;

    bool createStream();
    void closeStream();

    bool decodeFileToPcmFloatStereo(const std::string &filePath,
                                    std::vector<float> &outInterleaved,
                                    int32_t &outSampleRate) const;

    void prepareDspForSampleRate(int32_t sampleRate);
    void applyProfile(float &left, float &right);
    void runDynamicPunch(float &left, float &right);

    void pushFftSample(float sampleMono);
    void computeAndPublishFft();
    void initializeFftTables();

    std::shared_ptr<oboe::AudioStream> mAudioStream;

    std::shared_ptr<const std::vector<float>> mActiveTrack;
    std::atomic<uint64_t> mReadFrame{0};
    std::atomic<bool> mIsPlaying{false};

    std::atomic<int32_t> mTrackSampleRate{48000};
    std::atomic<Profile> mProfile{Profile::StudioFlat};

    Biquad mVocalEqL;
    Biquad mVocalEqR;
    Biquad mPunchShelfL;
    Biquad mPunchShelfR;

    float mCompEnvelope = 0.0f;
    float mCompThreshold = 0.35f;
    float mCompRatio = 3.0f;
    float mCompExponent = 0.6666667f;
    float mCompAttackCoeff = 0.0f;
    float mCompReleaseCoeff = 0.0f;

    std::array<float, kFftSize> mFftInput{};
    std::array<float, kFftSize> mFftWindow{};
    std::array<std::complex<float>, kFftSize / 2> mFftTwiddles{};
    int mFftWriteIndex = 0;

    std::array<float, kFftBins> mFftBuffers[2]{};
    std::atomic<int> mPublishedFftIndex{0};
};

extern "C" {
JNIEXPORT jboolean JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativeInitEngine(JNIEnv *env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativePlay(JNIEnv *env,
                                                                 jobject thiz,
                                                                 jstring file_path);

JNIEXPORT void JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativePause(JNIEnv *env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativeSetProfile(JNIEnv *env,
                                                                       jobject thiz,
                                                                       jint profile_id);

JNIEXPORT jfloatArray JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativeGetFftData(JNIEnv *env, jobject thiz);
}
