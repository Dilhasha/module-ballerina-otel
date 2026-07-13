# Copyright (c) 2026 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
#
# WSO2 Inc. licenses this file to you under the Apache License,
# Version 2.0 (the "License"); you may not use this file except
# in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Shrink-only configuration for producing a slim kotlin-stdlib jar that contains
# exactly the classes/members reachable from okhttp + okio (the only bundled
# libraries written in Kotlin). Names, attributes and bytecode are left untouched
# so the surviving classes are byte-identical to the originals.

# Note: preverification stays enabled; the JVM requires valid StackMapTable
# attributes in the rewritten class files.
-dontoptimize
-dontobfuscate
-keepattributes *
-keepparameternames
-dontnote **

# Roots: keep okhttp and okio in their entirety so that every kotlin-stdlib
# class/member any of their code paths may touch survives the shrink.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin metadata annotation must remain resolvable.
-keep class kotlin.Metadata { *; }

# kotlin.internal.PlatformImplementations is selected via Class.forName() based on
# the JVM version; keep the reflectively loaded implementations.
-keep class kotlin.internal.jdk8.JDK8PlatformImplementations { *; }
-keep class kotlin.internal.jdk7.JDK7PlatformImplementations { *; }

# NOTE ON KOTLIN MULTIFILE FACADES:
# kotlin-stdlib facades such as kotlin.collections.CollectionsKt inherit their static
# methods from a superclass chain of generated "part" classes (CollectionsKt__CollectionsKt,
# ...). A call site emits `invokestatic CollectionsKt.<method>` and ProGuard resolves it up
# that chain, so the referenced part-class members survive on their own; no blanket keep of
# the parts is needed here (that would defeat the shrink). Should a future okhttp/okio bump
# introduce a facade method the resolver misses, the size-reduction test suite will fail with
# a NoSuchMethodError naming the exact method, which can then be pinned with a targeted
# -keepclassmembers rule.

# Enum reflection helpers (Enum.valueOf / values via kotlin.enums or serialization).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Optional / compile-only dependencies of okhttp and okio that are absent at
# runtime on the JVM (Android, alternative TLS providers, GraalVM, annotations).
-dontwarn android.**
-dontwarn dalvik.**
-dontwarn com.android.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.internal.platform.**

# Kotlin 2.2 experimental kotlin.concurrent.atomics API: the AtomicInt/AtomicLong/
# AtomicReference/AtomicArray implementation classes live in the multi-release
# META-INF/versions/** tree (JDK9+ VarHandle based) which is stripped from the slim
# jar. They are internal to kotlin-stdlib and unreachable from okhttp/okio.
-dontwarn kotlin.concurrent.atomics.**

# EnhancedNullability is a synthetic marker the Kotlin compiler emits in signatures;
# it is not a real class on the classpath.
-dontwarn kotlin.jvm.internal.EnhancedNullability
