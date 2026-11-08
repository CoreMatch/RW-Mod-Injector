#!/bin/bash
# RW-Mod-Injector 安装器 (Linux v1, 设计 §4 决策 D4: 双击一次完成).
#
# 做法: 不修改任何客户端游戏文件 —— 在游戏目录旁新增
#   rw-mod-injector/            agent jar + 配置 + 状态
#   start-with-injector.sh      包装启动脚本 (复刻官方启动命令 + -javaagent)
# 玩家此后双击 start-with-injector.sh 启动游戏.
#
# 注意: 官方 rustedWarfareLinux.sh 把 "$@" 传给主类 (程序参数) 而不是
# JVM, 所以 Steam 启动项塞 -javaagent 无效 —— 必须走包装脚本 (或运行期
# attach, 后续增量).
#
# 用法: ./install.sh [游戏目录]
#   游戏目录缺省时按常见位置自动探测.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_SRC="$SCRIPT_DIR/../injector/build/rw-mod-injector.jar"
if [[ ! -f "$JAR_SRC" ]]; then
  echo "找不到 agent jar: $JAR_SRC (先跑 injector/build.sh)" >&2
  exit 2
fi

find_game_dir() {
  local candidates=(
    "${1:-}"
    "$HOME/Downloads/Rusted Warfare"
    "$HOME/.steam/steam/steamapps/common/Rusted Warfare"
    "$HOME/.local/share/Steam/steamapps/common/Rusted Warfare"
  )
  for d in "${candidates[@]}"; do
    if [[ -n "$d" && -f "$d/game-lib.jar" && -f "$d/rustedWarfareLinux.sh" ]]; then
      echo "$d"
      return 0
    fi
  done
  return 1
}

GAME_DIR="$(find_game_dir "${1:-}")" || {
  echo "未找到游戏目录 (需含 game-lib.jar 与 rustedWarfareLinux.sh); 请显式传路径" >&2
  exit 2
}
GAME_DIR="$(readlink -f "$GAME_DIR")"
echo "游戏目录: $GAME_DIR"

DEST="$GAME_DIR/rw-mod-injector"
mkdir -p "$DEST"
cp "$JAR_SRC" "$DEST/rw-mod-injector.jar"

# 配置 (已存在则不覆盖)
if [[ ! -f "$DEST/rw-mod-injector.properties" ]]; then
  cat > "$DEST/rw-mod-injector.properties" <<'EOF'
# RW-Mod-Injector 配置
# 控制面基址: 目标 Avalon 后端的 /v1 控制面 (默认端口 5125)
control.url=http://127.0.0.1:5125/v1
# 出口形态:
#   cascade = 装配完成后发 /goto <code> 走 Cascade 跳转 (生产形态)
#   direct  = 装配完成后直连 direct.target (直连/本地测试形态)
mode=direct
direct.target=127.0.0.1:5123
EOF
fi

# 包装启动脚本 (新增文件, 不动原脚本)
cat > "$GAME_DIR/start-with-injector.sh" <<EOF
#!/bin/bash
set -euo pipefail
cd "\$(dirname "\$0")"
if [ -d "jvm-linux" ]; then
  jvm="jvm-linux/bin/java"
else
  jvm="java"
fi
LD_LIBRARY_PATH=. exec \$jvm \\
  -javaagent:"\$PWD/rw-mod-injector/rw-mod-injector.jar=\$PWD/rw-mod-injector/rw-mod-injector.properties" \\
  -Dfile.encoding=UTF-8 -Djava.library.path=. \\
  -cp "game-lib.jar:libs/*" com.corrodinggames.rts.java.Main "\$@"
EOF
chmod +x "$GAME_DIR/start-with-injector.sh"

echo "完成. 用 $GAME_DIR/start-with-injector.sh 启动游戏 (带 Injector)."
echo "配置: $DEST/rw-mod-injector.properties"
