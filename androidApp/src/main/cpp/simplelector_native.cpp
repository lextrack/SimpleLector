#include <android/bitmap.h>
#include <android/imagedecoder.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>

namespace {

constexpr const char* kLogTag = "SimpleLectorNative";
constexpr jint kScaleModeFit = 0;
constexpr jint kScaleModeCenterCrop = 1;
constexpr jint kColorConfigArgb8888 = 0;
constexpr jint kColorConfigRgb565 = 1;

struct DecodeSize {
    int decode_width;
    int decode_height;
};

void logError(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message);
}

bool isRgb565(jint colorConfig) {
    return colorConfig == kColorConfigRgb565;
}

int androidBitmapFormatForColorConfig(jint colorConfig) {
    return isRgb565(colorConfig) ? ANDROID_BITMAP_FORMAT_RGB_565 : ANDROID_BITMAP_FORMAT_RGBA_8888;
}

const char* bitmapConfigFieldName(jint colorConfig) {
    return isRgb565(colorConfig) ? "RGB_565" : "ARGB_8888";
}

jobject createBitmapWithConfig(JNIEnv* env, jint width, jint height, jint colorConfig) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (bitmapClass == nullptr || configClass == nullptr) {
        logError("Unable to resolve Bitmap classes");
        return nullptr;
    }

    jfieldID configField = env->GetStaticFieldID(
        configClass,
        bitmapConfigFieldName(colorConfig),
        "Landroid/graphics/Bitmap$Config;"
    );
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (configField == nullptr || createBitmapMethod == nullptr) {
        env->DeleteLocalRef(configClass);
        env->DeleteLocalRef(bitmapClass);
        logError("Unable to resolve Bitmap factory members");
        return nullptr;
    }

    jobject bitmapConfig = env->GetStaticObjectField(configClass, configField);
    jobject bitmap = env->CallStaticObjectMethod(
        bitmapClass,
        createBitmapMethod,
        width,
        height,
        bitmapConfig
    );

    env->DeleteLocalRef(bitmapConfig);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
    return bitmap;
}

DecodeSize computeDecodeSize(
    int srcWidth,
    int srcHeight,
    jint maxWidth,
    jint maxHeight,
    jint scaleMode
) {
    const float widthRatio = static_cast<float>(maxWidth) / static_cast<float>(srcWidth);
    const float heightRatio = static_cast<float>(maxHeight) / static_cast<float>(srcHeight);
    float scale = 1.0f;

    if (scaleMode == kScaleModeCenterCrop) {
        scale = std::min(1.0f, std::max(widthRatio, heightRatio));
    } else {
        scale = std::min(1.0f, std::min(widthRatio, heightRatio));
    }

    return DecodeSize{
        std::max(1, static_cast<int>(std::lround(srcWidth * scale))),
        std::max(1, static_cast<int>(std::lround(srcHeight * scale))),
    };
}

jobject centerCropBitmap(
    JNIEnv* env,
    jobject bitmap,
    jint maxWidth,
    jint maxHeight
) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (bitmapClass == nullptr) {
        logError("Unable to resolve Bitmap class for crop");
        return bitmap;
    }

    jmethodID getWidthMethod = env->GetMethodID(bitmapClass, "getWidth", "()I");
    jmethodID getHeightMethod = env->GetMethodID(bitmapClass, "getHeight", "()I");
    jmethodID recycleMethod = env->GetMethodID(bitmapClass, "recycle", "()V");
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass,
        "createBitmap",
        "(Landroid/graphics/Bitmap;IIII)Landroid/graphics/Bitmap;"
    );
    if (getWidthMethod == nullptr || getHeightMethod == nullptr || createBitmapMethod == nullptr) {
        env->DeleteLocalRef(bitmapClass);
        logError("Unable to resolve Bitmap crop members");
        return bitmap;
    }

    const jint bitmapWidth = env->CallIntMethod(bitmap, getWidthMethod);
    const jint bitmapHeight = env->CallIntMethod(bitmap, getHeightMethod);
    const jint cropWidth = std::min(bitmapWidth, maxWidth);
    const jint cropHeight = std::min(bitmapHeight, maxHeight);
    if (cropWidth <= 0 || cropHeight <= 0 || (cropWidth == bitmapWidth && cropHeight == bitmapHeight)) {
        env->DeleteLocalRef(bitmapClass);
        return bitmap;
    }

    const jint offsetX = std::max(0, (bitmapWidth - cropWidth) / 2);
    const jint offsetY = std::max(0, (bitmapHeight - cropHeight) / 2);
    jobject cropped = env->CallStaticObjectMethod(
        bitmapClass,
        createBitmapMethod,
        bitmap,
        offsetX,
        offsetY,
        cropWidth,
        cropHeight
    );
    if (cropped != nullptr && recycleMethod != nullptr) {
        env->CallVoidMethod(bitmap, recycleMethod);
        env->DeleteLocalRef(bitmap);
        env->DeleteLocalRef(bitmapClass);
        return cropped;
    }

    env->DeleteLocalRef(bitmapClass);
    return bitmap;
}

jobject decodeBitmapInternal(
    JNIEnv* env,
    jbyteArray encodedBytes,
    jint maxWidth,
    jint maxHeight,
    jint scaleMode,
    jint colorConfig
) {
    if (encodedBytes == nullptr || maxWidth <= 0 || maxHeight <= 0) {
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

    const DecodeSize decodeSize = computeDecodeSize(srcWidth, srcHeight, maxWidth, maxHeight, scaleMode);
    if (AImageDecoder_setAndroidBitmapFormat(decoder, androidBitmapFormatForColorConfig(colorConfig)) != ANDROID_IMAGE_DECODER_SUCCESS) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        logError("Unable to configure bitmap output format");
        return nullptr;
    }

    if (AImageDecoder_setTargetSize(decoder, decodeSize.decode_width, decodeSize.decode_height) != ANDROID_IMAGE_DECODER_SUCCESS) {
        AImageDecoder_delete(decoder);
        env->ReleaseByteArrayElements(encodedBytes, encodedData, JNI_ABORT);
        logError("Unable to configure decoder target size");
        return nullptr;
    }

    jobject bitmap = createBitmapWithConfig(env, decodeSize.decode_width, decodeSize.decode_height, colorConfig);
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

    if (scaleMode == kScaleModeCenterCrop) {
        return centerCropBitmap(env, bitmap, maxWidth, maxHeight);
    }
    return bitmap;
}

jbyteArray compressBitmapToWebp(JNIEnv* env, jobject bitmap, jint quality) {
    if (bitmap == nullptr) return nullptr;

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass compressFormatClass = env->FindClass("android/graphics/Bitmap$CompressFormat");
    jclass byteArrayOutputStreamClass = env->FindClass("java/io/ByteArrayOutputStream");
    if (bitmapClass == nullptr || compressFormatClass == nullptr || byteArrayOutputStreamClass == nullptr) {
        logError("Unable to resolve classes for compress");
        return nullptr;
    }

    jfieldID webpLossyField = env->GetStaticFieldID(
        compressFormatClass,
        "WEBP_LOSSY",
        "Landroid/graphics/Bitmap$CompressFormat;"
    );
    jmethodID streamCtor = env->GetMethodID(byteArrayOutputStreamClass, "<init>", "()V");
    jmethodID compressMethod = env->GetMethodID(
        bitmapClass,
        "compress",
        "(Landroid/graphics/Bitmap$CompressFormat;ILjava/io/OutputStream;)Z"
    );
    jmethodID toByteArrayMethod = env->GetMethodID(byteArrayOutputStreamClass, "toByteArray", "()[B");
    jmethodID recycleMethod = env->GetMethodID(bitmapClass, "recycle", "()V");
    if (webpLossyField == nullptr || streamCtor == nullptr || compressMethod == nullptr || toByteArrayMethod == nullptr) {
        env->DeleteLocalRef(byteArrayOutputStreamClass);
        env->DeleteLocalRef(compressFormatClass);
        env->DeleteLocalRef(bitmapClass);
        logError("Unable to resolve Bitmap compress members");
        return nullptr;
    }

    jobject webpLossy = env->GetStaticObjectField(compressFormatClass, webpLossyField);
    jobject stream = env->NewObject(byteArrayOutputStreamClass, streamCtor);
    if (webpLossy == nullptr || stream == nullptr) {
        env->DeleteLocalRef(stream);
        env->DeleteLocalRef(webpLossy);
        env->DeleteLocalRef(byteArrayOutputStreamClass);
        env->DeleteLocalRef(compressFormatClass);
        env->DeleteLocalRef(bitmapClass);
        logError("Unable to allocate ByteArrayOutputStream");
        return nullptr;
    }

    const jboolean compressed = env->CallBooleanMethod(bitmap, compressMethod, webpLossy, quality, stream);
    if (recycleMethod != nullptr) {
        env->CallVoidMethod(bitmap, recycleMethod);
    }
    jbyteArray bytes = nullptr;
    if (compressed == JNI_TRUE) {
        bytes = static_cast<jbyteArray>(env->CallObjectMethod(stream, toByteArrayMethod));
    } else {
        logError("Bitmap.compress failed");
    }

    env->DeleteLocalRef(stream);
    env->DeleteLocalRef(webpLossy);
    env->DeleteLocalRef(byteArrayOutputStreamClass);
    env->DeleteLocalRef(compressFormatClass);
    env->DeleteLocalRef(bitmapClass);
    return bytes;
}

}  // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_simplelector_AndroidNativeImageBridge_nativeDecodeBitmap(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray encodedBytes,
    jint maxWidth,
    jint maxHeight,
    jint scaleMode,
    jint colorConfig
) {
    return decodeBitmapInternal(env, encodedBytes, maxWidth, maxHeight, scaleMode, colorConfig);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_simplelector_AndroidNativeImageBridge_nativeDecodeAndCompress(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray encodedBytes,
    jint maxWidth,
    jint maxHeight,
    jint scaleMode,
    jint colorConfig,
    jint quality
) {
    jobject bitmap = decodeBitmapInternal(env, encodedBytes, maxWidth, maxHeight, scaleMode, colorConfig);
    if (bitmap == nullptr) {
        return nullptr;
    }
    return compressBitmapToWebp(env, bitmap, quality);
}
