#!/usr/bin/env bash
# RW-Mod-Injector CI 构建 (无闭源 game-lib.jar 环境).
#
# 真实构建 (injector/build.sh) 以官方客户端 game-lib.jar 为编译期符号来源,
# 该字节码闭源、不入库、不分发 —— CI 里没有它. 本脚本改用 ci/stubs/ 的
# 最小签名桩满足 javac, 其余与 build.sh 同款: --release 8 字节码、
# shade ASM、Premain/Agent 双入口 manifest. 桩类不进产物.
#
# 用法: ci/build-ci.sh <asm-jar>
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "用法: $0 <asm-jar>" >&2
  exit 2
fi
ASM_JAR="$(readlink -f "$1")"
if [[ ! -f "$ASM_JAR" ]]; then
  echo "找不到 ASM jar: $ASM_JAR" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/injector/build"
STUB_CLASSES="$BUILD/ci-stub-classes"
CLASSES="$BUILD/ci-classes"
rm -rf "$STUB_CLASSES" "$CLASSES"
mkdir -p "$STUB_CLASSES" "$CLASSES"

# 1. 编译引擎符号桩 (仅编译期)
find "$ROOT/ci/stubs" -name '*.java' > "$BUILD/ci-stub-sources.txt"
javac --release 8 -Xlint:-options -d "$STUB_CLASSES" @"$BUILD/ci-stub-sources.txt"

# 2. 编译 injector 本体 (桩 + ASM 进类路径)
find "$ROOT/injector/src/main/java" -name '*.java' > "$BUILD/ci-sources.txt"
javac --release 8 -Xlint:-options \
  -cp "$STUB_CLASSES:$ASM_JAR" \
  -d "$CLASSES" @"$BUILD/ci-sources.txt"

# 3. 字节码目标复核: 全部类必须 major 52 (Java 8, 客户端 JVM 是 1.8)
bad=$(javap -verbose -cp "$CLASSES" com.corematch.rwmodinjector.Assembler \
  | grep -c 'major version: 52' || true)
if [[ "$bad" != "1" ]]; then
  echo "字节码目标不是 Java 8 (major 52)" >&2
  exit 1
fi

# 4. shade ASM + 打 agent jar (与 build.sh 同款 manifest; 桩类不进包)
(cd "$CLASSES" && unzip -oq "$ASM_JAR" 'org/objectweb/asm/*' 'module-info.class' 2>/dev/null || \
 cd "$CLASSES" && unzip -oq "$ASM_JAR" 'org/objectweb/asm/*')
rm -f "$CLASSES/module-info.class"

cat > "$BUILD/ci-manifest.txt" <<'EOF'
Manifest-Version: 1.0
Premain-Class: com.corematch.rwmodinjector.InjectorAgent
Agent-Class: com.corematch.rwmodinjector.InjectorAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
EOF

jar cfm "$BUILD/ci-rw-mod-injector.jar" "$BUILD/ci-manifest.txt" -C "$CLASSES" .
echo "OK: $BUILD/ci-rw-mod-injector.jar (CI build against stubs)"
