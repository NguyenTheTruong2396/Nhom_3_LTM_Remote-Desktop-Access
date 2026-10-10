#pragma once
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Class:     com_remote_server_nativeapi_JniBridge
 * Method:    testJni
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_com_remote_server_nativeapi_JniBridge_testJni
  (JNIEnv *, jclass);

/*
 * Class:     com_remote_server_nativeapi_JniBridge
 * Method:    captureFrame
 * Signature: (I)[B
 */
JNIEXPORT jbyteArray JNICALL Java_com_remote_server_nativeapi_JniBridge_captureFrame
  (JNIEnv *, jclass, jint quality);

#ifdef __cplusplus
}
#endif
