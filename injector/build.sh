#!/usr/bin/env bash
# RW-Mod-Injector agent 构建脚本.
#
# 用法: ./build.sh <游戏目录> [asm-jar 路径]
#   游戏目录: 官方客户端目录, 需含 game-lib.jar 与 libs/ (编译期符号来源,
#             不打进产物; 引擎字节码只作编译目标, 不修改不分发).
#   asm-jar:  可选, 默认取本机 m2 缓存的 org.ow2.asm:asm:9.7.
#
# 产物: build/rw-mod-injector.jar (Java 8 字节码, Premain/Agent 双入口,
#       shade 了 ASM —— 客户端 JVM 是 Java 8, ASM 9.7 字节码为 49 可承载).
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "用法: $0 <游戏目录> [asm-jar]" >&2
  exit 2
fi
GAME_DIR="$(readlink -f "$1")"
GAME_LIB="$GAME_DIR/game-lib.jar"
if [[ ! -f "$GAME_LIB" ]]; then
  echo "找不到 $GAME_LIB" >&2
  exit 2
fi
ASM_JAR="${2:-$HOME/.m2/repository/org/ow2/asm/asm/9.7/asm-9.7.jar}"
if [[ ! -f "$ASM_JAR" ]]; then
  echo "找不到 ASM jar: $ASM_JAR" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES"

find "$ROOT/src/main/java" -name '*.java' > "$BUILD/sources.txt"

# 引擎类签名引用了 android 兼容层 (libs/*), 一并进编译期类路径
javac --release 8 -Xlint:-options \
  -cp "$GAME_LIB:$GAME_DIR/libs/*:$ASM_JAR" \
  -d "$CLASSES" @"$BUILD/sources.txt"

# shade ASM (BSD, 保留版权声明) 进 agent jar
(cd "$CLASSES" && unzip -oq "$ASM_JAR" 'org/objectweb/asm/*' 'module-info.class' 2>/dev/null || \
 cd "$CLASSES" && unzip -oq "$ASM_JAR" 'org/objectweb/asm/*')
rm -f "$CLASSES/module-info.class"

cat > "$BUILD/manifest.txt" <<'EOF'
Manifest-Version: 1.0
Premain-Class: com.corematch.rwmodinjector.InjectorAgent
Agent-Class: com.corematch.rwmodinjector.InjectorAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
EOF

jar cfm "$BUILD/rw-mod-injector.jar" "$BUILD/manifest.txt" -C "$CLASSES" .
echo "OK: $BUILD/rw-mod-injector.jar"
