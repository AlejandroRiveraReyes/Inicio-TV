#!/usr/bin/env bash
# Instala Inicio TV y lo pone como launcher predeterminado.
#
#   ./scripts/instalar.sh <IP-de-la-TV>            modo normal (recomendado)
#   ./scripts/instalar.sh <IP-de-la-TV> --fuerte   además desactiva el launcher original
#                                                  (deja de ejecutarse en segundo plano y de cargar anuncios)
#
# Todo es reversible con ./scripts/revertir.sh <IP-de-la-TV>
set -euo pipefail

PKG="com.launchertv.home"
HOME_ACTIVITY="$PKG/$PKG.HomeAlias"
STATE_FILE="/data/local/tmp/launchertv_desactivados.txt"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/release/app-release.apk}"

IP="${1:-}"
MODE="${2:-}"
ADB=(adb)
if [[ -n "$IP" && "$IP" != --* ]]; then
  [[ "$IP" == *:* ]] || IP="$IP:5555"
  adb connect "$IP" >/dev/null
  echo "Conectado a $IP (si la TV muestra un aviso de depuración, acéptalo y vuelve a lanzar el script)."
  ADB=(adb -s "$IP")
else
  MODE="$IP"
fi

if [[ ! -f "$APK" ]]; then
  echo "Compilando APK..."
  (cd "$ROOT" && ./gradlew assembleRelease -q)
fi

echo "Instalando Inicio TV..."
"${ADB[@]}" install -r "$APK"
"${ADB[@]}" shell pm enable "$HOME_ACTIVITY" >/dev/null 2>&1 || true

echo "Estableciendo Inicio TV como launcher predeterminado..."
"${ADB[@]}" shell cmd package set-home-activity "$HOME_ACTIVITY" || \
  echo "  (esta versión de Android no admite set-home-activity; prueba con --fuerte)"

if [[ "$MODE" == "--fuerte" ]]; then
  echo "Modo fuerte: desactivando el launcher original (reversible)..."
  # Solo los launchers de Google. Otras apps que también se declaran HOME (p. ej. Orange TV)
  # son apps de uso normal y no se tocan.
  for P in com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher; do
    "${ADB[@]}" shell pm path "$P" 2>/dev/null | grep -q package: || continue
    echo "  - desactivando $P"
    "${ADB[@]}" shell pm disable-user --user 0 "$P" >/dev/null
    "${ADB[@]}" shell "grep -qx '$P' $STATE_FILE 2>/dev/null || echo '$P' >> $STATE_FILE"
  done
fi

"${ADB[@]}" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Listo. Pulsa Inicio en el mando."
