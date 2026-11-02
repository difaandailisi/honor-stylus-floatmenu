#!/system/bin/sh
# =============================================================================
#  stylus_float_menu.sh   v1.1.0
#  荣耀手写笔「悬浮快捷菜单」诊断 / 显示 / 反相保活
# -----------------------------------------------------------------------------
#  v1.1.0 修订依据 = 真机实测（CGA-W00 / Android 16），详见 README.md 第 13 节：
#    · 判定只读 Settings.Global  -> 可伪造（已实测成功）
#    · 本机 stylus_support_floating_capsule=null 仍可显示 -> 不再判 FAIL
#    · am start-service 需签名级权限 -> shell 必失败（已降级为可选诊断）
#    · service call AGPService 需 root，且令牌/事务码存疑 -> 标为未验证
#    · 键是事件驱动（写入一次长期有效）-> 保活改为低频「反相器」
#  环境: adb shell / Shizuku(uid 2000) 即可，root 非必需
#  用法: sh stylus_float_menu.sh help
# =============================================================================

VERSION='1.1.0'
WIN='FloatBallWindowView'
SVC='com.hihonor.stylus.floatmenu/.floatball.service.StylusFloatBallService'
ACT='com.hihonor.stylus.floating.capsule'

# ---- 参与判定的 Settings.Global 键 ----
K_SWITCH='stylus_support_floating_capsule'
K_CONN='honor_bt_pencil_connection_state'
K_PSTATE='key_bt_pencil_state'
K_ATTACH='key_pencil_attach_state'
K_ATYPE='key_pencil_attach_type'
K_WAKE='honor_stylus_wake_state'
K_BATT='honor_bt_pencil_battery_level'
K_CHARGE='honor_bt_pencil_charging_state'
K_MODEL='honor_bt_pencil_model_number'
K_PID='HnBlePeripheralCfgpencilpid'
K_PIDKB='HnBlePeripheralCfgkeyboardpid'
K_PIDDIC='HnBlePeripheralCfgkbsuphostdicpid'
K_KBNAME='HnBlePeripheralCfgkbname'

# ---- Settings.System 表（实测：疑似真开关在这里） ----
S_ENABLE='stylus_enable'
S_INTRO='stylus_state_introduce'
S_MINRR='min_refresh_rate'
S_MAXRR='peak_refresh_rate'

BACKUP='/data/local/tmp/stylus_float_menu_backup.conf'
BKEYS="$K_SWITCH $K_CONN $K_PSTATE $K_ATTACH $K_ATYPE $K_CHARGE"

# ---------------------------------------------------------------- 基础封装
SU=''
if [ "$(id -u 2>/dev/null)" != '0' ] && command -v su >/dev/null 2>&1 && su -c true >/dev/null 2>&1; then
    SU='su'
fi

_s()    { if [ -n "$SU" ]; then su -c "settings $*"; else settings $*; fi; }
gget()  { _s get global "$1" 2>/dev/null | tr -d '\r'; }
gput()  { _s put global "$1" "$2" >/dev/null 2>&1; }
gdel()  { _s delete global "$1" >/dev/null 2>&1; }
yget()  { _s get system "$1" 2>/dev/null | tr -d '\r'; }
yput()  { _s put system "$1" "$2" >/dev/null 2>&1; }
ydel()  { _s delete system "$1" >/dev/null 2>&1; }
prop()  { getprop "$1" 2>/dev/null; }
say()   { echo "$*"; }
hr()    { echo '------------------------------------------------------------'; }

# ------------------------------------------------------ 黑盒观测（最可靠）
win_count() { dumpsys window windows 2>/dev/null | grep -c "$WIN"; }
win_vis()   { dumpsys window windows 2>/dev/null | grep -A14 "$WIN" | grep -iE 'mViewVisibility|mObscured'; }
ext_stylus(){ dumpsys input 2>/dev/null | grep -i 'ExternalStylusPresence'; }

verify() {
    c=$(win_count)
    say "  窗口数($WIN) = $c"
    if [ "$c" != '0' ]; then
        say '  可见性(须 mViewVisibility=0x0 才是真显示):'
        win_vis | sed 's/^/    /'
    else
        say '  -> 未显示（窗口不存在）'
    fi
    say '  系统是否认定有外部笔:'
    ext_stylus | sed 's/^/    /'
}

dump() {
    hr; say "全量速查   $(date '+%F %T')"; hr
    say '[Settings.Global]'
    for k in $K_SWITCH $K_CONN $K_PSTATE $K_ATTACH $K_ATYPE $K_CHARGE $K_BATT $K_WAKE $K_MODEL; do
        printf '  %-34s= %s\n' "$k" "$(gget "$k")"
    done
    say '[Settings.System]'
    for k in $S_ENABLE $S_INTRO $S_MINRR $S_MAXRR; do
        printf '  %-34s= %s\n' "$k" "$(yget "$k")"
    done
    say '[PID 白名单]'
    for k in $K_PID $K_PIDKB $K_PIDDIC $K_KBNAME; do
        printf '  %-34s= %s\n' "$k" "$(gget "$k")"
    done
    hr
}

# -------------------------------------------------------------------- 判定器
v_switch() {
    case "$1" in
        1)       echo 'OK' ;;
        null|'') if [ "$2" = '1' ]; then
                     echo '未知(Global=null, 但 System.stylus_enable=1, 实测仍可显示)'
                 else
                     echo '未知(Global=null, 以实测为准)'
                 fi ;;
        *)       echo "非 1 (当前 $1)" ;;
    esac
}
v_conn()   { case "$1" in 0|null|'') echo 'FAIL -> 笔未被蓝牙包接管' ;; *) echo 'OK' ;; esac; }
v_pstate() { case "$1" in 2|4) echo 'OK' ;; *) echo "FAIL -> 当前 $1, 需 2 或 4" ;; esac; }
v_attach() {
    if [ "$1" = '1' ] && [ "$2" = '1' ]; then
        echo 'FAIL -> 吸附态, 球被强制隐藏'
    else
        echo 'OK'
    fi
}

# -------------------------------------------------------------------- 动作件
trigger_show() {
    # 1 -> 0 跳变：ContentObserver 只在值变化时回调；实测“一次写入长期有效”
    gput "$K_PSTATE" 2
    gput "$K_ATYPE" 1;  sleep 0.3
    gput "$K_ATTACH" 1; sleep 0.4
    gput "$K_ATYPE" 0;  sleep 0.3
    gput "$K_ATTACH" 0; sleep 0.4
}
trigger_hide() {
    gput "$K_ATYPE" 1;  sleep 0.3
    gput "$K_ATTACH" 1; sleep 0.4
}
send_state() {
    say '  [注意] 该服务需要签名级权限 com.hihonor.stylus.floatcapsule.STYLUS_MENU，shell 通常必然失败；'
    say '         且实测服务本身一直在运行，一般不需要拉起。'
    am start-service -n "$SVC" -a "$ACT" --ei key_pencil_state 2 2>&1 | sed 's/^/    /'
}

# -------------------------------------------------------------------- 子命令
cmd_check() {
    hr; say "stylus_float_menu v$VERSION   检查   $(date '+%F %T')"; hr

    say '[环境]'
    printf '  %-30s= %s\n' 'ro.product.model'         "$(prop ro.product.model)"
    printf '  %-30s= %s\n' 'ro.build.characteristics' "$(prop ro.build.characteristics)"
    printf '  %-30s= %s\n' 'msc.sys.oemName'          "$(prop msc.sys.oemName)"
    printf '  %-30s= %s\n' 'ro.build.magic_api_level' "$(prop ro.build.magic_api_level)"
    if [ -n "$SU" ]; then s='有 su'; else s='无 su (adb/Shizuku, 够用)'; fi
    printf '  %-30s= %s\n' '权限' "$s"

    say ''
    say '[显示状态（黑盒实测，最可靠）]'
    verify

    say ''
    say '[判定相关键]'
    sw=$(gget "$K_SWITCH"); cn=$(gget "$K_CONN"); ps=$(gget "$K_PSTATE")
    at=$(gget "$K_ATTACH"); ay=$(gget "$K_ATYPE"); se=$(yget "$S_ENABLE")
    printf '  %-34s= %-8s %s\n' "$K_SWITCH"        "$sw" "$(v_switch "$sw" "$se")"
    printf '  %-34s= %-8s %s\n' "$S_ENABLE(System)" "$se" '(实测：疑似真开关)'
    printf '  %-34s= %-8s %s\n' "$K_CONN"          "$cn" "$(v_conn "$cn")"
    printf '  %-34s= %-8s %s\n' "$K_PSTATE"        "$ps" "$(v_pstate "$ps")"
    printf '  %-34s= %-8s %s\n' "$K_ATTACH"        "$at" "$(v_attach "$at" "$ay")"
    printf '  %-34s= %-8s (反相器信号源: 磁吸=1 / 取下=0)\n' "$K_CHARGE" "$(gget "$K_CHARGE")"

    say ''
    say '[PID 白名单（四项须非空，否则 updateConfig 直接失败）]'
    for k in $K_PID $K_PIDKB $K_PIDDIC $K_KBNAME; do
        v=$(gget "$k")
        if [ -z "$v" ] || [ "$v" = 'null' ]; then
            printf '  %-32s= %-22s !! 空\n' "$k" "$v"
        else
            printf '  %-32s= %-22s OK\n' "$k" "$v"
        fi
    done
    say '  换算: CD52=0x1091=4241   ALITA=0x10a5=4261'

    say ''
    say '[提示] 本机实测 app 判定日志被荣耀限流，logcat 排障不可用；请用 verify / dump 做黑盒验证。'
    hr
}

cmd_backup() {
    if ! { echo "# stylus_float_menu backup $(date '+%F %T')"
           for k in $BKEYS; do echo "G $k=$(gget "$k")"; done
           echo "S $S_MINRR=$(yget "$S_MINRR")"
           echo "S $S_MAXRR=$(yget "$S_MAXRR")"; } > "$BACKUP" 2>/dev/null; then
        BACKUP='/sdcard/stylus_float_menu_backup.conf'
        { echo "# stylus_float_menu backup $(date '+%F %T')"
          for k in $BKEYS; do echo "G $k=$(gget "$k")"; done
          echo "S $S_MINRR=$(yget "$S_MINRR")"
          echo "S $S_MAXRR=$(yget "$S_MAXRR")"; } > "$BACKUP"
    fi
    say "已备份原值 -> $BACKUP"
}

cmd_rollback() {
    [ -f "$BACKUP" ] || { say "没有备份文件: $BACKUP"; return 1; }
    while read -r line; do
        case "$line" in
            G\ *) k=${line#G }; k=${k%%=*}; v=${line#*=}
                  if [ -z "$v" ] || [ "$v" = 'null' ]; then gdel "$k"; else gput "$k" "$v"; fi
                  say "  global  $k = $v" ;;
            S\ *) k=${line#S }; k=${k%%=*}; v=${line#*=}
                  if [ -z "$v" ] || [ "$v" = 'null' ]; then ydel "$k"; else yput "$k" "$v"; fi
                  say "  system  $k = $v" ;;
        esac
    done < "$BACKUP"
    say '已按备份回滚完成。'
}

cmd_show() {
    say '== 让悬浮球显示（实测有效路径）=='
    [ -f "$BACKUP" ] || cmd_backup >/dev/null
    say '  写入 key_bt_pencil_state=2，并伪造一次“取下笔”(1 -> 0 跳变)'
    trigger_show
    sleep 1
    say ''
    verify
}

cmd_hide() {
    say '== 隐藏悬浮球 =='
    say '  （恢复系统语义：判定为“吸附态”即隐藏）'
    trigger_hide
    sleep 1
    verify
}

cmd_fps() {
    case "$1" in
        on|120)
            say '锁定 min/peak_refresh_rate = 120（System）'
            yput "$S_MINRR" 120; yput "$S_MAXRR" 120
            say '  !! 实测提示：荣耀未必读这两个键；更可靠的做法是'
            say '     设置 -> 显示和亮度 -> 屏幕刷新率 -> 选“高”，并关闭“智能/动态刷新率”' ;;
        off|auto)
            say '删除 min/peak_refresh_rate，交还系统'
            ydel "$S_MINRR"; ydel "$S_MAXRR" ;;
        scene-on|scene-off)
            say '!! 未验证，且大概率不可用（三重原因）：'
            say '   1) 需要 root；Shizuku 只有 shell'
            say '   2) service call 不写 interface token，而源码是 writeInterfaceToken("AGPService")'
            say '   3) 事务码 0x7a 随固件版本变化'
            if [ -n "$SU" ]; then
                if [ "$1" = 'scene-on' ]; then v=60; else v=0; fi
                su -c "service call AGPService 0x7a i32 $v"
            else
                say '   当前无 su，跳过。'
            fi ;;
        *) say "用法: $0 fps on | off | scene-on | scene-off" ;;
    esac
}

cmd_diag() {
    hr; say '蓝牙 / 输入侧证据'; hr
    say '[配对与链路]'
    dumpsys bluetooth_manager 2>/dev/null | grep -iE 'bonded|bond_state|Pencil' | head -20 | sed 's/^/  /'
    say '[HID 设备类 / VID / PID]'
    dumpsys input 2>/dev/null | grep -A6 'Pencil' | grep -iE 'Classes|Identifier|UniqueId|Pencil' | head -20 | sed 's/^/  /'
    say '[系统是否认定有外部笔]'
    ext_stylus | sed 's/^/  /'
    say '[服务是否在运行 / 权限]'
    dumpsys activity services com.hihonor.stylus.floatmenu 2>/dev/null | grep -iE 'ServiceRecord|permission' | head -10 | sed 's/^/  /'
    hr
}

# ------------------------------------------------------- 反相器（替代保活）
cmd_invert() {
    mode=${1:-auto}; iv=${2:-30}
    say "== 反相器：监听 $K_CHARGE  ->  写 $K_ATTACH   (间隔 ${iv}s, Ctrl+C 退出) =="
    say '   设计要点：单向、异键。绝不监听“自己要写的键”，否则会自触发死循环。'
    if [ "$mode" = 'always' ]; then
        say '   模式 always：恒写 0（球始终显示）'
    else
        say '   模式 auto  ：磁吸(charge=1) -> 写 0 显示；取下(charge=0) -> 写 1 隐藏'
    fi
    say '   实测说明：这些键是事件驱动、写入一次长期有效，故间隔 30s 足够，开销极低。'
    trap 'say ""; say "已退出反相器。"; exit 0' INT TERM
    n=0
    while :; do
        n=$((n + 1))
        ch=$(gget "$K_CHARGE"); cur=$(gget "$K_ATTACH")
        if [ "$mode" = 'always' ]; then
            want=0
        else
            case "$ch" in 1) want=0 ;; *) want=1 ;; esac
        fi
        if [ "$cur" != "$want" ]; then gput "$K_ATTACH" "$want"; act="写入 $want"; else act='无需改'; fi
        printf '\r  [%s] 第 %d 轮  charge=%s  attach=%s  -> %s   ' "$(date '+%H:%M:%S')" "$n" "$ch" "$cur" "$act"
        sleep "$iv"
    done
}

cmd_logcat() {
    say '（实测：本机 app 判定日志被限流，本命令基本无用，仅作备用）'
    logcat -v time 2>/dev/null | grep -E 'StylusFloatingCapsuleService|BtPencilStateHelper|PencilController|FloatingBallUtils'
}

cmd_help() {
    cat <<'EOF'
stylus_float_menu.sh v1.1.0 —— 荣耀手写笔「悬浮快捷菜单」工具

用法: sh stylus_float_menu.sh <命令> [参数]

  check                只读检查：环境 + 显示状态(黑盒) + 各判定键 + 白名单
  verify               只看显示状态：窗口数 + mViewVisibility + ExternalStylusPresence
  dump                 一次性打印所有相关键（Global / System / 白名单）
  diag                 蓝牙/输入侧证据：配对、HID 类、VID/PID、服务权限
  show                 让悬浮球显示（写 state=2 + 伪造“取下笔” 1->0）★ 实测有效
  hide                 隐藏悬浮球（伪造“吸附态”）
  invert auto|always [秒]   反相器：监听 charging_state 写 attach_state（默认 auto 30s）
  backup / rollback    备份 / 回滚
  fps on|off           锁定 / 恢复 120Hz（System 键，荣耀未必读）
  fps scene-on|off     root 且未验证：service call AGPService 0x7a
  sendstate            可选诊断：am start-service（需签名权限，shell 必失败）
  watch [always|idle] [秒]   ★自动看门狗：笔已连接但球不存在时自动补显示（默认 always 15s）
  logcat               备用：抓日志（本机可能被限流）
  help                 本帮助

实测要点（详见 README 第 13 节）：
  · 只读 Settings.Global 的判定可以被伪造 -> 球能出来，但不等于拿到 120Hz
  · stylus_support_floating_capsule 在 Global 可能为 null，此时以实测为准
  · 球是否真显示要看 mViewVisibility，不能只看窗口数
  · 写值前请务必先 backup

PowerShell 用户注意：整条 shell 参数必须用单引号，避免 $( ) 被本地展开。
EOF
}

# ------------------------------------------------- 自动看门狗（v1.1.1 新增）
# 目的：让 CD54/CD54S 这类“不自动出现”的笔，连接后自动出现悬浮球
# 判定：窗口数 = 0 且 笔已连接  ->  写 state=2 + 伪造“取下笔”(1->0)
cmd_watch() {
    mode=${1:-always}; iv=${2:-15}
    say "== 自动看门狗 (模式 $mode, 间隔 ${iv}s, Ctrl+C 退出) =="
    say '   逻辑：只要“笔已连接 + 球不存在”就补一刀，等价于 MP3 的原生自动出现'
    if [ "$mode" = 'idle' ]; then
        say '   模式 idle  ：仅当笔未吸附(charging_state=0)时才补（更贴近原生语义）'
    else
        say '   模式 always：不论吸附与否都补（即“一连上就出现”）'
    fi
    say '   注意：本模式会覆盖 hide；要隐藏请先停掉看门狗'
    trap 'say ""; say "已退出看门狗。"; exit 0' INT TERM
    n=0; acts=0
    while :; do
        n=$((n + 1))
        cn=$(gget "$K_CONN"); ps=$(gget "$K_PSTATE")
        ch=$(gget "$K_CHARGE"); w=$(win_count)
        state='待机'
        if [ "$w" = '0' ]; then
            if [ "$cn" = '0' ] || [ "$cn" = 'null' ] || [ -z "$cn" ]; then
                state='笔未连接, 不动作'
            else
                if [ "$mode" = 'idle' ] && [ "$ch" = '1' ]; then
                    state='已吸附(charge=1), 跳过'
                else
                    case "$ps" in 2|4) ;; *) gput "$K_PSTATE" 2 ;; esac
                    trigger_show
                    acts=$((acts + 1))
                    sleep 1
                    state="已补显示 第 ${acts} 次 (窗口数=$(win_count))"
                fi
            fi
        else
            state="球已在 (窗口数=$w)"
        fi
        printf '\r  [%s] 第 %d 轮  conn=%s  pstate=%s  charge=%s  %s   ' \
            "$(date '+%H:%M:%S')" "$n" "$cn" "$ps" "$ch" "$state"
        sleep "$iv"
    done
}

# ---------------------------------------------------------------------- main
case "$1" in
    check|status)        cmd_check ;;
    verify)              verify ;;
    dump)                dump ;;
    diag)                cmd_diag ;;
    show)                cmd_show ;;
    hide)                cmd_hide ;;
    invert|ka|keepalive) shift; cmd_invert "$@" ;;
    backup)              cmd_backup ;;
    rollback)            cmd_rollback ;;
    fps)                 shift; cmd_fps "$@" ;;
    sendstate)           send_state ;;
    watch)               shift; cmd_watch "$@" ;;
    logcat|log)          cmd_logcat ;;
    help|-h|--help|'')   cmd_help ;;
    *) say "未知命令: $1"; echo ''; cmd_help; exit 1 ;;
esac
