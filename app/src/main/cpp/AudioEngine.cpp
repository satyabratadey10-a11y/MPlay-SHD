#include "AudioEngine.h"

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {
constexpr const char *kTag = "TuneAiAudioEngine";

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)

constexpr float kPi = 3.14159265358979323846f;

std::unique_ptr<AudioPlayer> gPlayer;

inline float clampUnit(float x) {
    return std::max(-1.0f, std::min(1.0f, x));
}

inline bool startsWith(const char *text, const char *prefix) {
    if (text == nullptr || prefix == nullptr) return false;
    return std::strncmp(text, prefix, std::strlen(prefix)) == 0;
}

inline float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}
}

void AudioPlayer::Biquad::reset() {
    z1 = 0.0f;
    z2 = 0.0f;
}

float AudioPlayer::Biquad::process(float x) {
    const float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
}

void AudioPlayer::Biquad::setupPeaking(float sampleRate, float centerHz, float q, float gainDb) {
    const float A = std::sqrt(dbToLinear(gainDb));
    const float w0 = 2.0f * kPi * centerHz / sampleRate;
    const float alpha = std::sin(w0) / (2.0f * q);
    const float cosW0 = std::cos(w0);

    const float nb0 = 1.0f + alpha * A;
    const float nb1 = -2.0f * cosW0;
    const float nb2 = 1.0f - alpha * A;
    const float na0 = 1.0f + alpha / A;
    const float na1 = -2.0f * cosW0;
    const float na2 = 1.0f - alpha / A;

    b0 = nb0 / na0;
    b1 = nb1 / na0;
    b2 = nb2 / na0;
    a1 = na1 / na0;
    a2 = na2 / na0;
    reset();
}

void AudioPlayer::Biquad::setupLowShelf(float sampleRate, float centerHz, float slope, float gainDb) {
    const float A = std::sqrt(dbToLinear(gainDb));
    const float w0 = 2.0f * kPi * centerHz / sampleRate;
    const float cosW0 = std::cos(w0);
    const float sinW0 = std::sin(w0);
    const float alpha = sinW0 / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / slope - 1.0f) + 2.0f);
    const float twoSqrtAAlpha = 2.0f * std::sqrt(A) * alpha;

    const float nb0 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 + twoSqrtAAlpha);
    const float nb1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosW0);
    const float nb2 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 - twoSqrtAAlpha);
    const float na0 = (A + 1.0f) + (A - 1.0f) * cosW0 + twoSqrtAAlpha;
    const float na1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosW0);
    const float na2 = (A + 1.0f) + (A - 1.0f) * cosW0 - twoSqrtAAlpha;

    b0 = nb0 / na0;
    b1 = nb1 / na0;
    b2 = nb2 / na0;
    a1 = na1 / na0;
    a2 = na2 / na0;
    reset();
}

AudioPlayer::AudioPlayer() = default;

AudioPlayer::~AudioPlayer() {
    closeStream();
}

bool AudioPlayer::initEngine() {
    return createStream();
}

bool AudioPlayer::createStream() {
    if (mAudioStream) {
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(kOutputChannels)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK || !stream) {
        LOGE("Failed opening output stream: %s", oboe::convertToText(result));
        return false;
    }

    mAudioStream = std::move(stream);
    prepareDspForSampleRate(mAudioStream->getSampleRate());

    const oboe::Result requestStart = mAudioStream->requestStart();
    if (requestStart != oboe::Result::OK) {
        LOGE("Failed starting output stream: %s", oboe::convertToText(requestStart));
        closeStream();
        return false;
    }

    LOGI("Audio stream started: sampleRate=%d, framesPerBurst=%d",
         mAudioStream->getSampleRate(),
         mAudioStream->getFramesPerBurst());
    return true;
}

void AudioPlayer::closeStream() {
    if (!mAudioStream) {
        return;
    }
    mAudioStream->requestStop();
    mAudioStream->close();
    mAudioStream.reset();
}

bool AudioPlayer::playFile(const std::string &filePath) {
    if (!mAudioStream && !createStream()) {
        return false;
    }

    std::vector<float> decoded;
    int32_t decodedRate = 0;
    if (!decodeFileToPcmFloatStereo(filePath, decoded, decodedRate) || decoded.empty()) {
        LOGE("Decoding failed for path: %s", filePath.c_str());
        return false;
    }

    auto track = std::make_shared<const std::vector<float>>(std::move(decoded));
    std::atomic_store(&mActiveTrack, track);
    mTrackSampleRate.store(decodedRate, std::memory_order_relaxed);
    mReadFrame.store(0, std::memory_order_relaxed);
    mIsPlaying.store(true, std::memory_order_release);

    return true;
}

void AudioPlayer::pause() {
    mIsPlaying.store(false, std::memory_order_release);
}

void AudioPlayer::setProfile(Profile profile) {
    mProfile.store(profile, std::memory_order_release);
}

std::vector<float> AudioPlayer::getFftDataSnapshot() const {
    const int idx = mPublishedFftIndex.load(std::memory_order_acquire);
    return std::vector<float>(mFftBuffers[idx].begin(), mFftBuffers[idx].end());
}

bool AudioPlayer::decodeFileToPcmFloatStereo(const std::string &filePath,
                                             std::vector<float> &outInterleaved,
                                             int32_t &outSampleRate) const {
    AMediaExtractor *extractor = AMediaExtractor_new();
    if (!extractor) {
        LOGE("AMediaExtractor_new failed");
        return false;
    }

    if (AMediaExtractor_setDataSource(extractor, filePath.c_str()) != AMEDIA_OK) {
        LOGE("Unable to open data source: %s", filePath.c_str());
        AMediaExtractor_delete(extractor);
        return false;
    }

    const size_t trackCount = AMediaExtractor_getTrackCount(extractor);
    ssize_t selectedTrack = -1;
    AMediaFormat *selectedFormat = nullptr;

    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat *fmt = AMediaExtractor_getTrackFormat(extractor, i);
        const char *mime = nullptr;
        if (fmt && AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) && startsWith(mime, "audio/")) {
            selectedTrack = static_cast<ssize_t>(i);
            selectedFormat = fmt;
            break;
        }
        if (fmt) AMediaFormat_delete(fmt);
    }

    if (selectedTrack < 0 || !selectedFormat) {
        LOGE("No audio track found");
        AMediaExtractor_delete(extractor);
        return false;
    }

    const char *mime = nullptr;
    if (!AMediaFormat_getString(selectedFormat, AMEDIAFORMAT_KEY_MIME, &mime) || !mime) {
        LOGE("Missing MIME in selected track format");
        AMediaFormat_delete(selectedFormat);
        AMediaExtractor_delete(extractor);
        return false;
    }

    int32_t sampleRate = 48000;
    (void)AMediaFormat_getInt32(selectedFormat, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);

    int32_t channels = 2;
    (void)AMediaFormat_getInt32(selectedFormat, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
    channels = std::max(1, channels);

    AMediaCodec *codec = AMediaCodec_createDecoderByType(mime);
    if (!codec) {
        LOGE("AMediaCodec_createDecoderByType failed for %s", mime);
        AMediaFormat_delete(selectedFormat);
        AMediaExtractor_delete(extractor);
        return false;
    }

    if (AMediaExtractor_selectTrack(extractor, selectedTrack) != AMEDIA_OK) {
        LOGE("AMediaExtractor_selectTrack failed");
        AMediaCodec_delete(codec);
        AMediaFormat_delete(selectedFormat);
        AMediaExtractor_delete(extractor);
        return false;
    }

    media_status_t status = AMediaCodec_configure(codec, selectedFormat, nullptr, nullptr, 0);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(selectedFormat);
        AMediaExtractor_delete(extractor);
        return false;
    }

    status = AMediaCodec_start(codec);
    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed: %d", status);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(selectedFormat);
        AMediaExtractor_delete(extractor);
        return false;
    }

    constexpr int64_t kTimeoutUs = 8000;
    bool inputEos = false;
    bool outputEos = false;
    int32_t pcmEncoding = 2;

    while (!outputEos) {
        if (!inputEos) {
            const ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec, kTimeoutUs);
            if (inputIndex >= 0) {
                size_t inputBufSize = 0;
                uint8_t *inputBuf = AMediaCodec_getInputBuffer(codec, static_cast<size_t>(inputIndex), &inputBufSize);
                const ssize_t sampleSize = AMediaExtractor_readSampleData(extractor, inputBuf, inputBufSize);
                if (sampleSize < 0) {
                    AMediaCodec_queueInputBuffer(codec,
                                                 static_cast<size_t>(inputIndex),
                                                 0,
                                                 0,
                                                 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputEos = true;
                } else {
                    const int64_t presentationTimeUs = AMediaExtractor_getSampleTime(extractor);
                    AMediaCodec_queueInputBuffer(codec,
                                                 static_cast<size_t>(inputIndex),
                                                 0,
                                                 static_cast<size_t>(sampleSize),
                                                 presentationTimeUs,
                                                 0);
                    AMediaExtractor_advance(extractor);
                }
            }
        }

        AMediaCodecBufferInfo info{};
        const ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec, &info, kTimeoutUs);
        if (outputIndex >= 0) {
            size_t outSize = 0;
            uint8_t *out = AMediaCodec_getOutputBuffer(codec, static_cast<size_t>(outputIndex), &outSize);

            if (out && info.size > 0) {
                const uint8_t *raw = out + info.offset;
                if (pcmEncoding == 4) {
                    const size_t totalSamples = static_cast<size_t>(info.size) / sizeof(float);
                    const float *f32 = reinterpret_cast<const float *>(raw);
                    for (size_t i = 0; i < totalSamples; i += channels) {
                        const float l = clampUnit(f32[i]);
                        const float r = (channels > 1) ? clampUnit(f32[i + 1]) : l;
                        outInterleaved.push_back(l);
                        outInterleaved.push_back(r);
                    }
                } else {
                    const size_t totalSamples = static_cast<size_t>(info.size) / sizeof(int16_t);
                    const int16_t *s16 = reinterpret_cast<const int16_t *>(raw);
                    for (size_t i = 0; i < totalSamples; i += channels) {
                        const float l = static_cast<float>(s16[i]) / 32768.0f;
                        const float r = (channels > 1)
                                            ? static_cast<float>(s16[i + 1]) / 32768.0f
                                            : l;
                        outInterleaved.push_back(l);
                        outInterleaved.push_back(r);
                    }
                }
            }

            if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
                outputEos = true;
            }

            AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(outputIndex), false);
        } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *newFmt = AMediaCodec_getOutputFormat(codec);
            if (newFmt) {
                (void)AMediaFormat_getInt32(newFmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
                (void)AMediaFormat_getInt32(newFmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);
                (void)AMediaFormat_getInt32(newFmt, AMEDIAFORMAT_KEY_PCM_ENCODING, &pcmEncoding);
                channels = std::max(1, channels);
                AMediaFormat_delete(newFmt);
            }
        }
    }

    AMediaCodec_stop(codec);
    AMediaCodec_delete(codec);
    AMediaFormat_delete(selectedFormat);
    AMediaExtractor_delete(extractor);

    outSampleRate = sampleRate;
    return !outInterleaved.empty();
}

void AudioPlayer::prepareDspForSampleRate(int32_t sampleRate) {
    const float fs = static_cast<float>(std::max(8000, sampleRate));

    mVocalEqL.setupPeaking(fs, 2200.0f, 0.85f, 3.5f);
    mVocalEqR.setupPeaking(fs, 2200.0f, 0.85f, 3.5f);

    mPunchShelfL.setupLowShelf(fs, 130.0f, 0.75f, 2.2f);
    mPunchShelfR.setupLowShelf(fs, 130.0f, 0.75f, 2.2f);

    constexpr float attackMs = 8.0f;
    constexpr float releaseMs = 95.0f;
    mCompAttackCoeff = std::exp(-1.0f / (fs * attackMs * 0.001f));
    mCompReleaseCoeff = std::exp(-1.0f / (fs * releaseMs * 0.001f));
    mCompEnvelope = 0.0f;
}

void AudioPlayer::runDynamicPunch(float &left, float &right) {
    left = mPunchShelfL.process(left);
    right = mPunchShelfR.process(right);

    const float peak = std::max(std::abs(left), std::abs(right));
    if (peak > mCompEnvelope) {
        mCompEnvelope = mCompAttackCoeff * mCompEnvelope + (1.0f - mCompAttackCoeff) * peak;
    } else {
        mCompEnvelope = mCompReleaseCoeff * mCompEnvelope + (1.0f - mCompReleaseCoeff) * peak;
    }

    float gain = 1.0f;
    if (mCompEnvelope > mCompThreshold) {
        const float over = mCompEnvelope / mCompThreshold;
        const float compressed = std::pow(over, (1.0f - 1.0f / mCompRatio));
        gain = 1.0f / compressed;
    }

    left *= gain;
    right *= gain;

    left *= 1.08f;
    right *= 1.08f;
}

void AudioPlayer::applyProfile(float &left, float &right) {
    switch (mProfile.load(std::memory_order_relaxed)) {
        case Profile::StudioFlat:
            break;
        case Profile::VocalPresence:
            left = mVocalEqL.process(left);
            right = mVocalEqR.process(right);
            break;
        case Profile::DynamicPunch:
            runDynamicPunch(left, right);
            break;
    }

    left = clampUnit(left);
    right = clampUnit(right);
}

void AudioPlayer::pushFftSample(float sampleMono) {
    mFftInput[mFftWriteIndex++] = sampleMono;
    if (mFftWriteIndex >= kFftSize) {
        mFftWriteIndex = 0;
        computeAndPublishFft();
    }
}

void AudioPlayer::computeAndPublishFft() {
    std::array<std::complex<float>, kFftSize> x{};

    for (int i = 0; i < kFftSize; ++i) {
        const float w = 0.5f * (1.0f - std::cos(2.0f * kPi * static_cast<float>(i) / static_cast<float>(kFftSize - 1)));
        x[i] = std::complex<float>(mFftInput[i] * w, 0.0f);
    }

    int j = 0;
    for (int i = 1; i < kFftSize; ++i) {
        int bit = kFftSize >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            std::swap(x[i], x[j]);
        }
    }

    for (int len = 2; len <= kFftSize; len <<= 1) {
        const float ang = -2.0f * kPi / static_cast<float>(len);
        const std::complex<float> wlen(std::cos(ang), std::sin(ang));
        for (int i = 0; i < kFftSize; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            for (int k = 0; k < len / 2; ++k) {
                const std::complex<float> u = x[i + k];
                const std::complex<float> v = x[i + k + len / 2] * w;
                x[i + k] = u + v;
                x[i + k + len / 2] = u - v;
                w *= wlen;
            }
        }
    }

    const int writeIdx = 1 - mPublishedFftIndex.load(std::memory_order_relaxed);
    constexpr float kEps = 1e-9f;

    for (int b = 0; b < kFftBins; ++b) {
        const int fftIndex = 1 + b * ((kFftSize / 2 - 1) / kFftBins);
        const float mag = std::abs(x[fftIndex]) / static_cast<float>(kFftSize);
        const float db = 20.0f * std::log10(mag + kEps);
        const float normalized = std::clamp((db + 72.0f) / 72.0f, 0.0f, 1.0f);
        mFftBuffers[writeIdx][b] = normalized;
    }

    mPublishedFftIndex.store(writeIdx, std::memory_order_release);
}

oboe::DataCallbackResult AudioPlayer::onAudioReady(oboe::AudioStream *audioStream,
                                                   void *audioData,
                                                   int32_t numFrames) {
    (void)audioStream;
    auto *out = static_cast<float *>(audioData);

    auto track = std::atomic_load(&mActiveTrack);
    const bool playing = mIsPlaying.load(std::memory_order_acquire);

    if (!track || track->empty() || !playing) {
        std::memset(out, 0, sizeof(float) * static_cast<size_t>(numFrames) * kOutputChannels);
        return oboe::DataCallbackResult::Continue;
    }

    const size_t totalFrames = track->size() / kOutputChannels;
    uint64_t frameIndex = mReadFrame.load(std::memory_order_relaxed);

    for (int32_t f = 0; f < numFrames; ++f) {
        if (frameIndex >= totalFrames) {
            out[f * 2] = 0.0f;
            out[f * 2 + 1] = 0.0f;
            mIsPlaying.store(false, std::memory_order_release);
            continue;
        }

        float left = (*track)[static_cast<size_t>(frameIndex) * 2];
        float right = (*track)[static_cast<size_t>(frameIndex) * 2 + 1];

        applyProfile(left, right);

        out[f * 2] = left;
        out[f * 2 + 1] = right;

        pushFftSample(0.5f * (left + right));
        ++frameIndex;
    }

    mReadFrame.store(frameIndex, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

bool AudioPlayer::onError(oboe::AudioStream *audioStream, oboe::Result error) {
    LOGE("Oboe stream error: %s", oboe::convertToText(error));
    if (audioStream) {
        audioStream->close();
    }
    mAudioStream.reset();
    return createStream();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativeInitEngine(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!gPlayer) {
        gPlayer = std::make_unique<AudioPlayer>();
    }
    return static_cast<jboolean>(gPlayer->initEngine());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativePlay(JNIEnv *env,
                                                                 jobject thiz,
                                                                 jstring file_path) {
    (void)thiz;
    if (!gPlayer || !file_path) {
        return JNI_FALSE;
    }

    const char *cPath = env->GetStringUTFChars(file_path, nullptr);
    if (!cPath) {
        return JNI_FALSE;
    }

    const bool ok = gPlayer->playFile(cPath);
    env->ReleaseStringUTFChars(file_path, cPath);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativePause(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (gPlayer) {
        gPlayer->pause();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativeSetProfile(JNIEnv *env,
                                                                       jobject thiz,
                                                                       jint profile_id) {
    (void)env;
    (void)thiz;
    if (!gPlayer) {
        return;
    }

    AudioPlayer::Profile profile = AudioPlayer::Profile::StudioFlat;
    if (profile_id == static_cast<jint>(AudioPlayer::Profile::VocalPresence)) {
        profile = AudioPlayer::Profile::VocalPresence;
    } else if (profile_id == static_cast<jint>(AudioPlayer::Profile::DynamicPunch)) {
        profile = AudioPlayer::Profile::DynamicPunch;
    }

    gPlayer->setProfile(profile);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tuneai_audio_nativebridge_NativeAudioEngine_nativeGetFftData(JNIEnv *env, jobject thiz) {
    (void)thiz;
    if (!gPlayer) {
        return env->NewFloatArray(0);
    }

    const std::vector<float> fft = gPlayer->getFftDataSnapshot();
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(fft.size()));
    if (!out || fft.empty()) {
        return out;
    }

    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(fft.size()), fft.data());
    return out;
}
