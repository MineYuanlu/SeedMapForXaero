/* 平台分发 shim: jni.h 统一 include "jni_md.h"，按目标平台选实现。
 * 原始文件来自 OpenJDK (GPLv2 + Classpath exception)，见各 *_jni_md.h 头部。 */
#ifndef _JAVASOFT_JNI_MD_DISPATCH_H_
#define _JAVASOFT_JNI_MD_DISPATCH_H_

#if defined(_WIN32)
#include "win32_jni_md.h"
#elif defined(__APPLE__)
#include "darwin_jni_md.h"
#else
#include "linux_jni_md.h"
#endif

#endif /* !_JAVASOFT_JNI_MD_DISPATCH_H_ */
