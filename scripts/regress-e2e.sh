#!/usr/bin/env bash
# RW-Mod-Injector 真环境回归: 一条命令起整套环境并验证主链路.
#
# 环境: 真客户端 + 两个真 Avalon (大厅 + 模组房) + 真 Cascade, 全部起在
# 临时目录; 对 Avalon / Cascade 仓库只读 (只 go build, 不改仓库与配置).
#
# 流程: 起环境 -> 客户端连大厅 -> 装配 (下载/校验/物化/离房重载/重连)
#       -> /goto 进模组房 -> 等自动 restore -> 验证快照态与敏感文案零误报.
#
# 用法:
#   bash scripts/regress-e2e.sh
#
# 环境变量 (全部可选, 端口/目录可覆盖以防与机器上其它会话撞车):
#   RWMI_CLIENT_DIR       游戏客户端目录 (默认 ~/Downloads/Rusted Warfare)
#   RWMI_AVALON_DIR       Avalon 检出 (默认: 本仓同级 ../Avalon), 只读构建
#   RWMI_AVALON_BIN       直接给 avalon 二进制, 给了就不构建
#   RWMI_CASCADE_DIR      Cascade 检出 (默认 ~/Cascade), 只读构建
#   RWMI_CASCADE_BIN      直接给 cascade 二进制, 给了就不构建
#   RWMI_HELPER_JAR       协议匹配的 helper jar (缺省按已知位置探测)
#   RWMI_TEST_MOD_DIR     模组房提供的测试模组目录 (缺省探测)
#   RWMI_SEED_DATA_DIR    Avalon data/ 种子目录 (提供 gameData 等, 缺省探测)
#   RWMI_TMP              环境临时目录 (默认 mktemp -d)
#   RWMI_LOBBY_PORT / RWMI_LOBBY_CONTROL_PORT
#   RWMI_MODDED_PORT / RWMI_MODDED_CONTROL_PORT
#   RWMI_CASCADE_GAME_PORT / RWMI_CASCADE_JUMP_PORT / RWMI_CASCADE_HTTP_PORT
#   RWMI_AUTORESTORE_SEC  装配完成后多少秒自动 restore (默认 60)
#   RWMI_KEEP=1           结束后保留环境 (排障用; 客户端态仍会还原)
#
# 退出码: 0 = 全部通过; 1 = 有检查失败或前置缺失. 任何一步缺失都会明确
# 报出缺什么, 不静默跳过.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------------------------------------------------------- 默认值 ---
CLIENT_DIR="${RWMI_CLIENT_DIR:-$HOME/Downloads/Rusted Warfare}"
AVALON_DIR="${RWMI_AVALON_DIR:-$(dirname "$REPO_ROOT")/Avalon}"
CASCADE_DIR="${RWMI_CASCADE_DIR:-$HOME/Cascade}"
AVALON_BIN="${RWMI_AVALON_BIN:-}"
CASCADE_BIN="${RWMI_CASCADE_BIN:-}"
HELPER_JAR="${RWMI_HELPER_JAR:-}"
TEST_MOD_DIR="${RWMI_TEST_MOD_DIR:-}"
SEED_DATA_DIR="${RWMI_SEED_DATA_DIR:-}"

LOBBY_PORT="${RWMI_LOBBY_PORT:-5401}"
LOBBY_CONTROL_PORT="${RWMI_LOBBY_CONTROL_PORT:-5405}"
MODDED_PORT="${RWMI_MODDED_PORT:-5411}"
MODDED_CONTROL_PORT="${RWMI_MODDED_CONTROL_PORT:-5415}"
CASCADE_GAME_PORT="${RWMI_CASCADE_GAME_PORT:-5423}"
CASCADE_JUMP_PORT="${RWMI_CASCADE_JUMP_PORT:-5424}"
CASCADE_HTTP_PORT="${RWMI_CASCADE_HTTP_PORT:-5480}"
AUTORESTORE_SEC="${RWMI_AUTORESTORE_SEC:-60}"
KEEP="${RWMI_KEEP:-0}"

LOBBY_ROOM="RL01"
MODDED_ROOM="RM01"
LOBBY_BACKEND="reg-lobby"
MODDED_BACKEND="reg-modded"

TMP="${RWMI_TMP:-$(mktemp -d /tmp/rwmi-regress.XXXXXX)}"
CLIENT_LOG="$TMP/client.log"

# ---------------------------------------------------------------- 工具 ---
FAILURES=0
CHECKS=0
declare -a SUMMARY=()

check() { # check <名字> <0|1 是否通过> <细节>
  local name="$1" ok="$2" detail="${3:-}"
  CHECKS=$((CHECKS + 1))
  if [ "$ok" = 1 ]; then
    SUMMARY+=("[PASS] $name")
  else
    FAILURES=$((FAILURES + 1))
    SUMMARY+=("[FAIL] $name${detail:+ — $detail}")
  fi
}

die() { echo "REGRESS ABORT: $*" >&2; exit 1; }

wait_for() { # wait_for <秒> <命令...>: 轮询直到命令成功或超时
  local secs="$1"; shift
  local deadline=$(( $(date +%s) + secs ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

players_of() { # players_of <控制面端口> <房间码>
  curl -s --max-time 5 "http://127.0.0.1:$1/v1/rooms" | python3 -c "
import json,sys
try:
    rooms = json.load(sys.stdin)['rooms']
except Exception:
    print('ERR'); sys.exit(0)
for r in rooms:
    if r['code'] == '$2':
        print(r['players']); sys.exit(0)
print('ERR')"
}

room_players_is() { # room_players_is <控制面端口> <房间码> <期望值> (供 wait_for 用)
  [ "$(players_of "$1" "$2")" = "$3" ]
}

avalon_ready() { # avalon_ready <控制面端口>: /v1/health 报 ready (配置了
  # helper 时 ready 要求 helper 活着 —— 正是我们要等的)
  curl -s --max-time 5 "http://127.0.0.1:$1/v1/health" | grep -q '"status":"ready"'
}

cascade_sees_rooms() { # cascade 的房间快照里两个房间都在 = 后端控制面可达
  local body
  body=$(curl -s --max-time 5 "http://127.0.0.1:$CASCADE_HTTP_PORT/api/rooms") || return 1
  echo "$body" | grep -q "\"code\":\"$LOBBY_ROOM\"" \
    && echo "$body" | grep -q "\"code\":\"$MODDED_ROOM\""
}

cascade_backends_healthy() { # /api/backends 里两个后端都 healthy
  # cascade 要连续两次探测成功才置 healthy (directory.go), 而房间快照一次
  # 成功就会出现 —— 只等快照, 客户端会在"一次成功"窗口连入并被
  # "No available backend" 踢回 (本脚本首测即踩中).
  curl -s --max-time 5 "http://127.0.0.1:$CASCADE_HTTP_PORT/api/backends" | python3 -c "
import json,sys
try:
    bs = json.load(sys.stdin)['backends']
except Exception:
    sys.exit(1)
sys.exit(0 if bs and all(b['healthy'] for b in bs) else 1)"
}

skip() { SUMMARY+=("[SKIP] $1 — $2"); }

port_free() { ! ss -ltn 2>/dev/null | grep -qE ":$1\b"; }

# ---------------------------------------------------------------- 前置 ---
echo "== 前置检查 =="
[ -d "$CLIENT_DIR" ] || die "缺客户端目录: $CLIENT_DIR (RWMI_CLIENT_DIR)"
[ -f "$CLIENT_DIR/game-lib.jar" ] || die "$CLIENT_DIR 下没有 game-lib.jar, 不像游戏目录"
if pgrep -f 'rts[.]java[.]Main' >/dev/null; then
  die "游戏客户端正在运行 —— 回归要独占游戏目录, 请先关闭它"
fi
command -v python3 >/dev/null || die "缺 python3 (解析控制面 JSON 用)"
command -v openssl >/dev/null || die "缺 openssl (生成 Cascade 签名密钥用)"
command -v curl >/dev/null || die "缺 curl"

if [ -z "$AVALON_BIN" ]; then
  [ -f "$AVALON_DIR/go.mod" ] || die "缺 Avalon 检出: $AVALON_DIR (RWMI_AVALON_DIR), 或直接给 RWMI_AVALON_BIN"
  command -v go >/dev/null || die "要从源码构建 Avalon 需要 go 工具链 (或直接给 RWMI_AVALON_BIN)"
fi
if [ -z "$CASCADE_BIN" ]; then
  [ -f "$CASCADE_DIR/go.mod" ] || die "缺 Cascade 检出: $CASCADE_DIR (RWMI_CASCADE_DIR), 或直接给 RWMI_CASCADE_BIN"
  command -v go >/dev/null || die "要从源码构建 Cascade 需要 go 工具链 (或直接给 RWMI_CASCADE_BIN)"
fi

if [ -z "$HELPER_JAR" ]; then
  for c in /tmp/mapprobe/hstage/avalon-helper.jar \
           /tmp/mapprobe/stage/avalon-helper.jar \
           /tmp/rwmi-e2e/lobby/avalon-helper.jar; do
    [ -f "$c" ] && { HELPER_JAR="$c"; break; }
  done
fi
[ -n "$HELPER_JAR" ] && [ -f "$HELPER_JAR" ] \
  || die "缺 helper jar (RWMI_HELPER_JAR); 注意须与 Avalon main 的协议版本匹配 (当前为 v4)"

if [ -z "$TEST_MOD_DIR" ]; then
  for c in /tmp/rwmi-e2e/client-mod-backup/2296940532; do
    [ -d "$c" ] && { TEST_MOD_DIR="$c"; break; }
  done
fi
[ -n "$TEST_MOD_DIR" ] && [ -d "$TEST_MOD_DIR" ] \
  || die "缺测试模组目录 (RWMI_TEST_MOD_DIR): 模组房 data/mods 的内容, 客户端本地必须没有"

if [ -z "$SEED_DATA_DIR" ]; then
  for c in /tmp/rwmi-e2e/lobby/data /tmp/rwmi-e2e/modded/data; do
    [ -d "$c" ] && [ -d "$c/gameData" ] && { SEED_DATA_DIR="$c"; break; }
  done
fi
[ -n "$SEED_DATA_DIR" ] && [ -d "$SEED_DATA_DIR/gameData" ] \
  || die "缺 Avalon data/ 种子目录 (RWMI_SEED_DATA_DIR, 需含 gameData/): helper 开局要读它"

for p in "$LOBBY_PORT" "$LOBBY_CONTROL_PORT" "$MODDED_PORT" "$MODDED_CONTROL_PORT" \
         "$CASCADE_GAME_PORT" "$CASCADE_JUMP_PORT" "$CASCADE_HTTP_PORT"; do
  port_free "$p" || die "端口 $p 已被占用 —— 用 RWMI_*_PORT 环境变量换一组"
done
echo "前置齐全. 临时目录: $TMP"

# ------------------------------------------------------------- 构建/落盘 ---
mkdir -p "$TMP/bin"
if [ -n "$AVALON_BIN" ]; then
  cp "$AVALON_BIN" "$TMP/bin/avalon"
else
  echo "== 构建 Avalon (只读使用 $AVALON_DIR) =="
  (cd "$AVALON_DIR" && go build -o "$TMP/bin/avalon" ./cmd/avalon) || die "Avalon 构建失败"
fi
if [ -n "$CASCADE_BIN" ]; then
  cp "$CASCADE_BIN" "$TMP/bin/cascade"
else
  echo "== 构建 Cascade (只读使用 $CASCADE_DIR) =="
  (cd "$CASCADE_DIR" && go build -o "$TMP/bin/cascade" ./cmd/cascade) || die "Cascade 构建失败"
fi
chmod +x "$TMP/bin/avalon" "$TMP/bin/cascade"

MOD_NAME="$(basename "$TEST_MOD_DIR")"

write_avalon_instance() { # <目录> <端口> <控制端口> <后端ID> <房间码> <是否大厅> <服务名>
  local dir="$1" port="$2" ctrl="$3" bid="$4" room="$5" lobby="$6" name="$7"
  mkdir -p "$dir"
  cp "$TMP/bin/avalon" "$dir/avalon"
  cp "$HELPER_JAR" "$dir/avalon-helper.jar"
  cp -r "$SEED_DATA_DIR" "$dir/data"
  rm -f "$dir/data/Config.json" "$dir/data/ConfigServer.json"
  cat > "$dir/data/Config.json" <<EOF
{
  "defStartCommand": "start",
  "log": "INFO",
  "port": $port,
  "serverName": "$name",
  "subtitle": "",
  "autoUpList": false,
  "uplistEnable": false,
  "mixProtocolEnable": false,
  "rconMixEnable": false,
  "webMixEnable": false,
  "webPort": 0,
  "webToken": "regress$port",
  "gameHelperPath": "./avalon-helper.jar",
  "gameHelperArgs": ["-Xmx512m"],
  "cascadeEnable": true,
  "cascadeBackendId": "$bid",
  "cascadeRoomCode": "$room",
  "cascadeLobby": $lobby,
  "cascadeTrustMode": "open",
  "cascadeControlAddr": ":$ctrl"
}
EOF
}

echo "== 布置实例 =="
write_avalon_instance "$TMP/lobby" "$LOBBY_PORT" "$LOBBY_CONTROL_PORT" \
  "$LOBBY_BACKEND" "$LOBBY_ROOM" true "Regress-Lobby"
# 模组房同样用大厅模式 (cascadeLobby=true): 只建房不开局. 真开局 (room.start)
# 在 0 人时会被房间检查线程秒 Gr() (无 AI 0 人立即结束), 之后玩家无法再
# 进房. 实测验证过的主链路拓扑就是"双大厅": 引擎装载模组与开局与否无关,
# SERVER_INFO 单位核对在大厅态同样生效.
write_avalon_instance "$TMP/modded" "$MODDED_PORT" "$MODDED_CONTROL_PORT" \
  "$MODDED_BACKEND" "$MODDED_ROOM" true "Regress-Modded"
mkdir -p "$TMP/modded/data/mods"
rm -rf "$TMP/modded/data/mods/$MOD_NAME"
cp -r "$TEST_MOD_DIR" "$TMP/modded/data/mods/$MOD_NAME"

# Cascade: 全新配置 + 全新签名密钥 (不碰任何既有实例的配置)
mkdir -p "$TMP/cascade"
cp "$TMP/bin/cascade" "$TMP/cascade/cascade"
openssl genpkey -algorithm ed25519 -out "$TMP/cascade/proxy-ed25519-key.pem" 2>/dev/null \
  || die "生成 ed25519 密钥失败"
cat > "$TMP/cascade/config.yaml" <<EOF
version: 2

game_listen_addr: "127.0.0.1:$CASCADE_GAME_PORT"
http_listen_addr: "127.0.0.1:$CASCADE_HTTP_PORT"
public_game_host: "127.0.0.1"

db_type: "sqlite"
db_path: "data/cascade.db"

register_timeout_sec: 5
read_timeout_sec: 90
chat_rate_limit: 20

default_backend_id: "$LOBBY_BACKEND"
backends:
  - id: "$LOBBY_BACKEND"
    control_url: "http://127.0.0.1:$LOBBY_CONTROL_PORT"
    game_address: "127.0.0.1:$LOBBY_PORT"
    prefixes: ["RL"]
    trust_mode: "open"
    tls_mode: "plain"
  - id: "$MODDED_BACKEND"
    control_url: "http://127.0.0.1:$MODDED_CONTROL_PORT"
    game_address: "127.0.0.1:$MODDED_PORT"
    prefixes: ["RM"]
    trust_mode: "open"
    tls_mode: "plain"
    jump_port: $CASCADE_JUMP_PORT

proxy_issuer: "cascade"
proxy_key_id: "regress-key"
proxy_private_key_path: "proxy-ed25519-key.pem"

health_interval_sec: 5
room_snapshot_interval_sec: 5
pending_session_timeout_sec: 5
EOF

# ------------------------------------------------------- 客户端态备份/装配 ---
echo "== 准备客户端 (备份原态, 装当前仓的 injector) =="
BACKUP="$TMP/client-backup"
mkdir -p "$BACKUP"
HAD_INJECTOR=0
if [ -d "$CLIENT_DIR/rw-mod-injector" ]; then
  cp -r "$CLIENT_DIR/rw-mod-injector" "$BACKUP/rw-mod-injector"
  HAD_INJECTOR=1
fi
[ -f "$CLIENT_DIR/start-with-injector.sh" ] && cp "$CLIENT_DIR/start-with-injector.sh" "$BACKUP/"
[ -d "$CLIENT_DIR/mods/units" ] && cp -r "$CLIENT_DIR/mods/units" "$BACKUP/units"
[ -f "$CLIENT_DIR/preferences.ini" ] && cp "$CLIENT_DIR/preferences.ini" "$BACKUP/preferences.ini"

LOBBY_PID="" ; MODDED_PID="" ; CASCADE_PID="" ; CLIENT_PID=""
cleanup() {
  [ -n "$CLIENT_PID" ] && kill "$CLIENT_PID" 2>/dev/null
  pkill -f 'rts[.]java[.]Main' 2>/dev/null
  for pid in "$LOBBY_PID" "$MODDED_PID" "$CASCADE_PID"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
  done
  # 等进程真正退出 (新 avalon 优雅退出要送终 helper, 可能十几秒;
  # 不等的话残留进程会占住端口, 下一次运行在前置检查就撞车)
  local deadline=$(( $(date +%s) + 25 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local alive=0
    for pid in "$LOBBY_PID" "$MODDED_PID" "$CASCADE_PID"; do
      [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && alive=1
    done
    [ "$alive" = 0 ] && break
    sleep 1
  done
  pkill -f "$TMP/lobby" 2>/dev/null
  pkill -f "$TMP/modded" 2>/dev/null
  pkill -f "$TMP/cascade" 2>/dev/null
  restore_client
  if [ "$KEEP" = "1" ]; then
    echo "(RWMI_KEEP=1: 环境保留在 $TMP; 客户端态已还原)"
  else
    rm -rf "$TMP"
  fi
}
trap cleanup EXIT

restore_client() {
  rm -rf "$CLIENT_DIR/rw-mod-injector" "$CLIENT_DIR/mods/units/$MOD_NAME"
  if [ "$HAD_INJECTOR" = 1 ]; then
    cp -r "$BACKUP/rw-mod-injector" "$CLIENT_DIR/rw-mod-injector"
  else
    rm -f "$CLIENT_DIR/start-with-injector.sh"
  fi
  if [ -d "$BACKUP/units" ]; then
    rm -rf "$CLIENT_DIR/mods/units"
    cp -r "$BACKUP/units" "$CLIENT_DIR/mods/units"
  fi
  if [ -f "$BACKUP/preferences.ini" ]; then
    cp "$BACKUP/preferences.ini" "$CLIENT_DIR/preferences.ini"
  fi
}

"$REPO_ROOT/injector/build.sh" "$CLIENT_DIR" >/dev/null || die "injector 构建失败"
"$REPO_ROOT/installer/install.sh" >/dev/null || die "injector 安装失败"

rm -rf "$CLIENT_DIR/rw-mod-injector/store" "$CLIENT_DIR/rw-mod-injector/rw-mod-injector.state"
rm -rf "$CLIENT_DIR/mods/units/$MOD_NAME"
cat > "$CLIENT_DIR/rw-mod-injector/rw-mod-injector.properties" <<EOF
control.url=http://127.0.0.1:$MODDED_CONTROL_PORT/v1
mode=cascade
direct.target=127.0.0.1:$MODDED_PORT
e2e.autoconnect=127.0.0.1:$CASCADE_GAME_PORT
e2e.autojoin=$MODDED_ROOM
e2e.autorestore=$AUTORESTORE_SEC
EOF

# ---------------------------------------------------------------- 启动 ---
echo "== 启动 lobby / modded / cascade =="
setsid nohup sh -c "cd $TMP/lobby && sleep infinity | ./avalon > avalon.log 2>&1" &
setsid nohup sh -c "cd $TMP/modded && sleep infinity | ./avalon > avalon.log 2>&1" &
setsid nohup sh -c "cd $TMP/cascade && ./cascade > cascade.log 2>&1" &

wait_for 90 grep -q 'Start listening' "$TMP/lobby/avalon.log" \
  || die "lobby 90s 内未就绪, 日志: $TMP/lobby/avalon.log (RWMI_KEEP=1 重跑可留现场)"
wait_for 90 grep -q 'Start listening' "$TMP/modded/avalon.log" \
  || die "modded 90s 内未就绪, 日志: $TMP/modded/avalon.log"
wait_for 30 sh -c "ss -ltn 2>/dev/null | grep -qE ':$CASCADE_GAME_PORT\b'" \
  || die "cascade 30s 内未监听游戏口, 日志: $TMP/cascade/cascade.log"
LOBBY_PID=$(ss -ltnp 2>/dev/null | grep -E ":$LOBBY_PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
MODDED_PID=$(ss -ltnp 2>/dev/null | grep -E ":$MODDED_PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
CASCADE_PID=$(ss -ltnp 2>/dev/null | grep -E ":$CASCADE_GAME_PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)
wait_for 30 room_players_is "$LOBBY_CONTROL_PORT" "$LOBBY_ROOM" 0 \
  || die "lobby 控制面未就绪 (/v1/rooms 无 $LOBBY_ROOM)"
wait_for 30 room_players_is "$MODDED_CONTROL_PORT" "$MODDED_ROOM" 0 \
  || die "modded 控制面未就绪 (/v1/rooms 无 $MODDED_ROOM)"
# 关键门控: helper 就绪 (health ready) 且 cascade 已把两个后端纳入房间快照.
# 少了这步, 客户端会在后端尚不健康时连入, 被 cascade 以
# "No available backend" 踢回 (首次实测即踩中).
wait_for 90 avalon_ready "$LOBBY_CONTROL_PORT" \
  || die "lobby 的 helper 90s 内未就绪 (/v1/health 非 ready), 日志: $TMP/lobby/avalon.log"
wait_for 90 avalon_ready "$MODDED_CONTROL_PORT" \
  || die "modded 的 helper 90s 内未就绪 (/v1/health 非 ready), 日志: $TMP/modded/avalon.log"
wait_for 90 cascade_backends_healthy \
  || die "cascade 90s 内未把两个后端判为 healthy (/api/backends), 日志: $TMP/cascade/cascade.log"
wait_for 30 cascade_sees_rooms \
  || die "cascade 30s 内未见两个后端的房间快照 (/api/rooms), 日志: $TMP/cascade/cascade.log"
check "环境启动 (双 Avalon + Cascade + 健康门控)" 1

# ---------------------------------------------------------------- 主链路 ---
echo "== 启动客户端, 跑主链路 =="
(cd "$CLIENT_DIR" && setsid nohup ./start-with-injector.sh > "$CLIENT_LOG" 2>&1 &)
CLIENT_PID=$(sleep 3; pgrep -f 'rts[.]java[.]Main' | head -1)

MAIN_OK=0
if wait_for 240 grep -q 'done\. /inj restore' "$CLIENT_LOG"; then
  check "装配主链路 (清单→下载→校验→物化→离房重载→重连→/goto)" 1
  MAIN_OK=1
else
  check "装配主链路 (清单→下载→校验→物化→离房重载→重连→/goto)" 0 \
    "240s 内未见完成标记, 客户端日志: $CLIENT_LOG"
fi

if [ "$MAIN_OK" = 1 ]; then
  sleep 5
  P=$(players_of "$MODDED_CONTROL_PORT" "$MODDED_ROOM")
  [ "$P" = 1 ] && check "玩家进入模组房 ($MODDED_ROOM players=1)" 1 \
    || check "玩家进入模组房 ($MODDED_ROOM players=1)" 0 "实际 players=$P"

  # ------------------------------------------------------------- restore ---
  echo "== 等待 ${AUTORESTORE_SEC}s 自动 restore =="
  if wait_for $((AUTORESTORE_SEC + 150)) grep -q 'previous mod set restored' "$CLIENT_LOG"; then
    check "自动 restore 完成" 1
  else
    check "自动 restore 完成" 0 "超时未见 'previous mod set restored'"
  fi
  sleep 8
else
  skip "玩家进入模组房" "主链路未通过"
  skip "自动 restore 完成" "主链路未通过"
fi

# ------------------------------------------------------------ 断言电池 ---
# 敏感文案零误报: 这些只应在真实失败时出现, 正常流程命中即错报
SENSITIVE='Connect to the lobby first|failed during the unit reload|failed unexpectedly|assembly failed|restore failed'
HITS=$(grep -cE "$SENSITIVE" "$CLIENT_LOG" 2>/dev/null || true)
[ "${HITS:-0}" = 0 ] && check "敏感文案零误报 (未连大厅/重载失败/意外失败等)" 1 \
  || check "敏感文案零误报 (未连大厅/重载失败/意外失败等)" 0 "命中 $HITS 处: $(grep -E "$SENSITIVE" "$CLIENT_LOG" | head -2 | tr '\n' ' ')"

if [ "$MAIN_OK" = 1 ]; then
  MODLINE=$(grep -E '^modSettings:' "$CLIENT_DIR/preferences.ini" 2>/dev/null || true)
  case "$MODLINE" in
    *"$MOD_NAME|"*disabled*) check "restore 快照态: 新装模组 ($MOD_NAME) 已禁用" 1 ;;
    *) check "restore 快照态: 新装模组 ($MOD_NAME) 已禁用" 0 "modSettings=$MODLINE" ;;
  esac

  if grep -q 'snapshot.taken' "$CLIENT_DIR/rw-mod-injector/rw-mod-injector.state" 2>/dev/null; then
    check "restore 快照态: 快照键已全清" 0 "state 里仍有 snapshot.taken"
  else
    check "restore 快照态: 快照键已全清" 1
  fi

  P=$(players_of "$LOBBY_CONTROL_PORT" "$LOBBY_ROOM")
  [ "$P" = 0 ] && check "大厅无幽灵玩家 (干净离房回收)" 1 \
    || check "大厅无幽灵玩家 (干净离房回收)" 0 "实际 players=$P"

  # restore 后客户端重连跳转口回到模组房: 已注册但禁用的模组通过单位核对
  # (设计 0001 §2.2 实测补充), 玩家应仍在房内
  P=$(players_of "$MODDED_CONTROL_PORT" "$MODDED_ROOM")
  [ "$P" = 1 ] && check "restore 后玩家回到原房 (重连跳转口)" 1 \
    || check "restore 后玩家回到原房 (重连跳转口)" 0 "实际 players=$P"
else
  skip "restore 快照态与玩家回收断言" "主链路未通过"
fi

# ---------------------------------------------------------------- 摘要 ---
echo
echo "== RW-Mod-Injector 真环境回归摘要 =="
for line in "${SUMMARY[@]}"; do echo "$line"; done
echo
if [ "$FAILURES" = 0 ]; then
  echo "结果: PASS ($CHECKS/$CHECKS)"
  exit 0
else
  echo "结果: FAIL ($FAILURES/$CHECKS 项失败)"
  echo "现场: 客户端日志 $CLIENT_LOG; 各实例日志在 $TMP/*/ "
  exit 1
fi
