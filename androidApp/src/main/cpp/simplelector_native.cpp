#include <android/bitmap.h>
#include <android/imagedecoder.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>

namespace {

constexpr const char* kLogTag = "SimpleLectorNative";

void logError(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message);
}

jobject createArgb8888Bitmap(JNIEnv* env, jint width, jint height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (bitmapClass == nullptr || configClass == nullptr) {
        logError("Unable to resolve Bitmap classes");
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(
        configClass,
        "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;"
    );
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (argb8888Field == nullptr || createBitmapMethod == nullptr) {
        logError("Unable to resolve Bitmap factory members");
        return nullptr;
    }

    jobject argb8888Config = env->GetStaticObjectField(configClass, argb8888Field);
    jobject bitmap = env->CallStaticObjectMethod(
        bitmapClass,
        createBitmapMethod,
        width,
        height,
        argb8888Config
    );

    env->DeleteLocalRef(argb8888Config);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
    return bitmap;
}

}  // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_simplelector_AndroidNativeImageBridge_nativeDecodeScaledBitmap(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray encodedBytes,
    jint maxDimension
) {
    if (encodedBytes == nullptr || maxDimension <= 0) {
        return nullptr;
    }

    const jsize length = env->GetArrayLength(encodedBytes);
    if (length <= 0) {
        return nullptr;
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* encodedData = env->GetByteArrayElements(encodedBytes, &isCopy);
    if (encodedData == nullptr) {
        return nullptr;
    }

    AImageDecoder* decoder = nullptr;
    const int createResult = AImageDecoder_createFromBuffer(
        reinterpret_cast<void*>(encodedData),
        static_cast<size_t>(length),
        &decoder
    );
    if (createResult != ANDROID_IMAGE_DECODER_SUCCESS || decoder == nullptr) {
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        logError("AImageDecoder_createFromBuffer failed");
        return nullptr;
    }

    const AImageDecoderHeaderInfo* headerInfo = AImageDecoder_getHeaderInfo(decoder);
    const int srcWidth = AImageDecoderHeaderInfo_getWidth(headerInfo);
    const int srcHeight = AImageDecoderHeaderInfo_getHeight(headerInfo);
    if (srcWidth <= 0 || srcHeight <= 0) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        logError("Decoded image has invalid size");
        return nullptr;
    }

    const float scale = std::min(
        1.0f,
        static_cast<float>(maxDimension) / static_cast<float>(std::max(srcWidth, srcHeight))
    );
    const int targetWidth = std::max(1, static_cast<int>(std::lround(srcWidth * scale)));
    const int targetHeight = std::max(1, static_cast<int>(std::lround(srcHeight * scale)));

    if (AImageDecoder_setAndroidBitmapFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888) != ANDROID_IMAGE_DECODER_SUCCESS) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        logError("Unable to force RGBA_8888 output");
        return nullptr;
    }

    if (AImageDecoder_setTargetSize(decoder, targetWidth, targetHeight) != ANDROID_IMAGE_DECODER_SUCCESS) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        logError("Unable to configure decoder target size");
        return nullptr;
    }

    jobject bitmap = createArgb8888Bitmap(env, targetWidth, targetHeight);
    if (bitmap == nullptr) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        return nullptr;
    }

    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        env->DeleteLocalRef(bitmap);
        logError("AndroidBitmap_getInfo failed");
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        env->DeleteLocalRef(bitmap);
        logError("AndroidBitmap_lockPixels failed");
        return nullptr;
    }

    const int decodeResult = AImageDecoder_decodeImage(
        decoder,
        pixels,
        static_cast<size_t>(bitmapInfo.stride),
        static_cast<size_t>(bitmapInfo.stride * bitmapInfo.height)
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    AImageDecoder_delete(decoder);
    env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);

    if (decodeResult != ANDROID_IMAGE_DECODER_SUCCESS) {
        env->DeleteLocalRef(bitmap);
        logError("AImageDecoder_decodeImage failed");
        return nullptr;
    }

    return bitmap;
}
