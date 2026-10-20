// JNI 标准命名约定绑定 XsmNative（手写 stub），符号由 JVM 在 dlopen 后 dlsym 解析，
// 因此无需链接 libjvm；jni.h 来自 vendored src/main/c/jni/（GPLv2+Classpath exception）。
// 注意: 方法名中的 '_' 必须按 JNI 规则编码为 '_1'（短名规则），jextract 时代以内部类
// 规避；这里唯一含 '_' 的方法 xsmGetStructFEATURE_NUM 需手工写成 FEATURE_1NUM。
#include <jni.h>

#include <cstdint>
#include <cstring>

#include "all.h"

#define XSM_JNI_PREFIX(name) Java_bid_yuanlu_seedmap4xaero_client_nativeapi_XsmNative_##name

extern "C" {

JNIEXPORT jint JNICALL
Java_bid_yuanlu_seedmap4xaero_client_nativeapi_XsmNative_xsmGetStructFEATURE_1NUM(JNIEnv*, jclass) {
    return static_cast<jint>(xsmGetStructFEATURE_NUM());
}

JNIEXPORT jboolean JNICALL XSM_JNI_PREFIX(setGameVersion)(JNIEnv* env, jclass, jstring version) {
    if (version == nullptr) return JNI_FALSE;
    const char* v = env->GetStringUTFChars(version, nullptr);
    if (v == nullptr) return JNI_FALSE;
    const bool ok = setGameVersion(v);
    env->ReleaseStringUTFChars(version, v);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL XSM_JNI_PREFIX(setWorld)(JNIEnv*, jclass, jlong seed, jint dim) {
    setWorld(static_cast<uint64_t>(seed), dim);
}

JNIEXPORT jboolean JNICALL XSM_JNI_PREFIX(setBiomeColorTableNative)(JNIEnv*, jclass) {
    return setBiomeColorTableNative() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL XSM_JNI_PREFIX(setBiomeColorTable)(JNIEnv* env, jclass,
        jintArray colors, jint size) {
    if (colors == nullptr) return JNI_FALSE;
    jint* c = env->GetIntArrayElements(colors, nullptr);
    if (c == nullptr) return JNI_FALSE;
    const bool ok = setBiomeColorTable(reinterpret_cast<uint32_t*>(c), static_cast<uint32_t>(size));
    env->ReleaseIntArrayElements(colors, c, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL XSM_JNI_PREFIX(setBiomeDisabled)(JNIEnv* env, jclass,
        jbyteArray bitset, jint size) {
    if (bitset == nullptr) return JNI_FALSE;
    jbyte* b = env->GetByteArrayElements(bitset, nullptr);
    if (b == nullptr) return JNI_FALSE;
    const bool ok = setBiomeDisabled(reinterpret_cast<const uint8_t*>(b), static_cast<uint32_t>(size));
    env->ReleaseByteArrayElements(bitset, b, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(genCellImg)(JNIEnv* env, jclass, jint scale, jint worldX,
        jint worldZ, jint absY, jbyteArray data, jboolean light) {
    if (data == nullptr) return 1;
    jbyte* d = env->GetByteArrayElements(data, nullptr);
    if (d == nullptr) return 1;
    const uint32_t rc = genCellImg(static_cast<uint32_t>(scale), worldX, worldZ,
            static_cast<uint32_t>(absY), reinterpret_cast<unsigned char*>(d), light != JNI_FALSE);
    env->ReleaseByteArrayElements(data, d, 0);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(queryPoint)(JNIEnv* env, jclass, jint worldX, jint worldZ,
        jbyteArray biomeName, jint biomeNameLen, jintArray height) {
    if (biomeName == nullptr || height == nullptr) return 1;
    jbyte* name = env->GetByteArrayElements(biomeName, nullptr);
    jint* h = env->GetIntArrayElements(height, nullptr);
    if (name == nullptr || h == nullptr) {
        if (name != nullptr) env->ReleaseByteArrayElements(biomeName, name, JNI_ABORT);
        if (h != nullptr) env->ReleaseIntArrayElements(height, h, JNI_ABORT);
        return 1;
    }
    const uint32_t rc = queryPoint(worldX, worldZ, reinterpret_cast<char*>(name),
            static_cast<uint32_t>(biomeNameLen), reinterpret_cast<int32_t*>(h));
    env->ReleaseByteArrayElements(biomeName, name, 0);
    env->ReleaseIntArrayElements(height, h, 0);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(queryExactChunkHeight)(JNIEnv* env, jclass, jint chunkX,
        jint chunkZ, jintArray heightsOut) {
    if (heightsOut == nullptr) return 1;
    jint* h = env->GetIntArrayElements(heightsOut, nullptr);
    if (h == nullptr) return 1;
    const uint32_t rc = queryExactChunkHeight(chunkX, chunkZ, reinterpret_cast<int32_t*>(h));
    env->ReleaseIntArrayElements(heightsOut, h, 0);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(xsmGetStructureConfig)(JNIEnv* env, jclass,
        jint structureType, jintArray out, jfloatArray outRarity) {
    if (out == nullptr || outRarity == nullptr) return 0;
    jint* o = env->GetIntArrayElements(out, nullptr);
    jfloat* r = env->GetFloatArrayElements(outRarity, nullptr);
    if (o == nullptr || r == nullptr) {
        if (o != nullptr) env->ReleaseIntArrayElements(out, o, JNI_ABORT);
        if (r != nullptr) env->ReleaseFloatArrayElements(outRarity, r, JNI_ABORT);
        return 0;
    }
    const jint ok = xsmGetStructureConfig(structureType,
            reinterpret_cast<int32_t*>(&o[0]), reinterpret_cast<int32_t*>(&o[1]),
            reinterpret_cast<int32_t*>(&o[2]), reinterpret_cast<int32_t*>(&o[3]),
            reinterpret_cast<float*>(r));
    env->ReleaseIntArrayElements(out, o, 0);
    env->ReleaseFloatArrayElements(outRarity, r, 0);
    return ok;
}

JNIEXPORT void JNICALL XSM_JNI_PREFIX(queryRegionStructuresGrid)(JNIEnv* env, jclass,
        jint structureType, jint rx0, jint rz0, jint rx1, jint rz1,
        jint rx2, jint rz2, jint rx3, jint rz3,
        jbyteArray outFound, jintArray outBlockX, jintArray outBlockZ, jintArray outVariant) {
    if (outFound == nullptr || outBlockX == nullptr || outBlockZ == nullptr
            || outVariant == nullptr) return;
    jbyte* f = env->GetByteArrayElements(outFound, nullptr);
    jint* bx = env->GetIntArrayElements(outBlockX, nullptr);
    jint* bz = env->GetIntArrayElements(outBlockZ, nullptr);
    jint* vr = env->GetIntArrayElements(outVariant, nullptr);
    if (f == nullptr || bx == nullptr || bz == nullptr || vr == nullptr) {
        if (f != nullptr) env->ReleaseByteArrayElements(outFound, f, JNI_ABORT);
        if (bx != nullptr) env->ReleaseIntArrayElements(outBlockX, bx, JNI_ABORT);
        if (bz != nullptr) env->ReleaseIntArrayElements(outBlockZ, bz, JNI_ABORT);
        if (vr != nullptr) env->ReleaseIntArrayElements(outVariant, vr, JNI_ABORT);
        return;
    }
    queryRegionStructuresGrid(structureType, rx0, rz0, rx1, rz1, rx2, rz2, rx3, rz3,
            reinterpret_cast<int8_t*>(f), reinterpret_cast<int32_t*>(bx),
            reinterpret_cast<int32_t*>(bz), reinterpret_cast<int32_t*>(vr));
    env->ReleaseByteArrayElements(outFound, f, 0);
    env->ReleaseIntArrayElements(outBlockX, bx, 0);
    env->ReleaseIntArrayElements(outBlockZ, bz, 0);
    env->ReleaseIntArrayElements(outVariant, vr, 0);
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(queryStrongholdsRange)(JNIEnv* env, jclass, jint from,
        jint to, jintArray outBlockX, jintArray outBlockZ) {
    if (outBlockX == nullptr || outBlockZ == nullptr) return 0;
    jint* x = env->GetIntArrayElements(outBlockX, nullptr);
    jint* z = env->GetIntArrayElements(outBlockZ, nullptr);
    if (x == nullptr || z == nullptr) {
        if (x != nullptr) env->ReleaseIntArrayElements(outBlockX, x, JNI_ABORT);
        if (z != nullptr) env->ReleaseIntArrayElements(outBlockZ, z, JNI_ABORT);
        return 0;
    }
    const uint32_t n = queryStrongholdsRange(from, to,
            reinterpret_cast<int32_t*>(x), reinterpret_cast<int32_t*>(z));
    env->ReleaseIntArrayElements(outBlockX, x, 0);
    env->ReleaseIntArrayElements(outBlockZ, z, 0);
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(querySpawn)(JNIEnv* env, jclass, jintArray outXZ) {
    if (outXZ == nullptr || env->GetArrayLength(outXZ) < 2) return 0;
    jint* p = env->GetIntArrayElements(outXZ, nullptr);
    if (p == nullptr) return 0;
    const int32_t rc = querySpawn(reinterpret_cast<int32_t*>(&p[0]),
            reinterpret_cast<int32_t*>(&p[1]));
    env->ReleaseIntArrayElements(outXZ, p, 0);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(querySparseStructures)(JNIEnv* env, jclass,
        jint structureType, jint rx0, jint rz0, jint rx1, jint rz1,
        jint ex0, jint ez0, jint ex1, jint ez1,
        jlong start, jint cap, jintArray outBlockX, jintArray outBlockZ,
        jintArray outVariant, jlongArray outNext) {
    if (outBlockX == nullptr || outBlockZ == nullptr || outVariant == nullptr
            || outNext == nullptr) return 0;
    jint* bx = env->GetIntArrayElements(outBlockX, nullptr);
    jint* bz = env->GetIntArrayElements(outBlockZ, nullptr);
    jint* vr = env->GetIntArrayElements(outVariant, nullptr);
    jlong* next = env->GetLongArrayElements(outNext, nullptr);
    if (bx == nullptr || bz == nullptr || vr == nullptr || next == nullptr) {
        if (bx != nullptr) env->ReleaseIntArrayElements(outBlockX, bx, JNI_ABORT);
        if (bz != nullptr) env->ReleaseIntArrayElements(outBlockZ, bz, JNI_ABORT);
        if (vr != nullptr) env->ReleaseIntArrayElements(outVariant, vr, JNI_ABORT);
        if (next != nullptr) env->ReleaseLongArrayElements(outNext, next, JNI_ABORT);
        return 0;
    }
    const uint32_t n = querySparseStructures(structureType, rx0, rz0, rx1, rz1, ex0, ez0, ex1, ez1,
            static_cast<int64_t>(start), cap, reinterpret_cast<int32_t*>(bx),
            reinterpret_cast<int32_t*>(bz), reinterpret_cast<int32_t*>(vr),
            reinterpret_cast<int64_t*>(next));
    env->ReleaseIntArrayElements(outBlockX, bx, 0);
    env->ReleaseIntArrayElements(outBlockZ, bz, 0);
    env->ReleaseIntArrayElements(outVariant, vr, 0);
    env->ReleaseLongArrayElements(outNext, next, 0);
    return static_cast<jint>(n);
}

JNIEXPORT jboolean JNICALL XSM_JNI_PREFIX(xsmBiome2str)(JNIEnv* env, jclass, jint biomeId,
        jbyteArray out, jint outLen) {
    if (out == nullptr) return JNI_FALSE;
    jbyte* o = env->GetByteArrayElements(out, nullptr);
    if (o == nullptr) return JNI_FALSE;
    const bool ok = xsmBiome2str(biomeId, reinterpret_cast<char*>(o), static_cast<uint32_t>(outLen));
    env->ReleaseByteArrayElements(out, o, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL XSM_JNI_PREFIX(xsmQueryStructureLoot)(JNIEnv* env, jclass,
        jint structureType, jint blockX, jint blockZ, jint outCap, jintArray outData,
        jintArray outWritten, jintArray outChestCount, jbyteArray outPieceNames,
        jbyteArray outLootTables) {
    if (outData == nullptr || outWritten == nullptr || outChestCount == nullptr
            || outPieceNames == nullptr || outLootTables == nullptr) return -1;
    jint* data = env->GetIntArrayElements(outData, nullptr);
    jint* written = env->GetIntArrayElements(outWritten, nullptr);
    jint* chests = env->GetIntArrayElements(outChestCount, nullptr);
    jbyte* pieces = env->GetByteArrayElements(outPieceNames, nullptr);
    jbyte* tables = env->GetByteArrayElements(outLootTables, nullptr);
    if (data == nullptr || written == nullptr || chests == nullptr || pieces == nullptr
            || tables == nullptr) {
        if (data != nullptr) env->ReleaseIntArrayElements(outData, data, JNI_ABORT);
        if (written != nullptr) env->ReleaseIntArrayElements(outWritten, written, JNI_ABORT);
        if (chests != nullptr) env->ReleaseIntArrayElements(outChestCount, chests, JNI_ABORT);
        if (pieces != nullptr) env->ReleaseByteArrayElements(outPieceNames, pieces, JNI_ABORT);
        if (tables != nullptr) env->ReleaseByteArrayElements(outLootTables, tables, JNI_ABORT);
        return -1;
    }
    const jint rc = xsmQueryStructureLoot(structureType, blockX, blockZ, outCap,
            reinterpret_cast<int32_t*>(data), reinterpret_cast<int32_t*>(written),
            reinterpret_cast<int32_t*>(chests), reinterpret_cast<char*>(pieces),
            reinterpret_cast<char*>(tables));
    env->ReleaseIntArrayElements(outData, data, 0);
    env->ReleaseIntArrayElements(outWritten, written, 0);
    env->ReleaseIntArrayElements(outChestCount, chests, 0);
    env->ReleaseByteArrayElements(outPieceNames, pieces, 0);
    env->ReleaseByteArrayElements(outLootTables, tables, 0);
    return rc;
}

JNIEXPORT jboolean JNICALL XSM_JNI_PREFIX(xsmItemName)(JNIEnv* env, jclass, jint globalItemId,
        jbyteArray out, jint outLen) {
    if (out == nullptr) return JNI_FALSE;
    jbyte* o = env->GetByteArrayElements(out, nullptr);
    if (o == nullptr) return JNI_FALSE;
    const bool ok = xsmItemName(globalItemId, reinterpret_cast<char*>(o),
            static_cast<uint32_t>(outLen));
    env->ReleaseByteArrayElements(out, o, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL XSM_JNI_PREFIX(xsmEnchantmentName)(JNIEnv* env, jclass,
        jint enchantmentId, jbyteArray out, jint outLen) {
    if (out == nullptr) return JNI_FALSE;
    jbyte* o = env->GetByteArrayElements(out, nullptr);
    if (o == nullptr) return JNI_FALSE;
    const bool ok = xsmEnchantmentName(enchantmentId, reinterpret_cast<char*>(o),
            static_cast<uint32_t>(outLen));
    env->ReleaseByteArrayElements(out, o, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
