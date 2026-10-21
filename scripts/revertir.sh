#!/usr/bin/env bash
# Devuelve la TV a su launcher original.
#
#   ./scripts/revertir.sh <IP-de-la-TV>                 reactiva el original (Inicio TV queda instalado)
#   ./scripts/revertir.sh <IP-de-la-TV> --desinstalar   además desinstala Inicio TV
set -euo pipefail

PKG="com.launchertv.home"
STATE_FILE="/data/local/tmp/launchertv_desactivados.txt"

IP="${1:-}"
MODE="${2:-}"
ADB=(adb)
if [[ -n "$IP" && "$IP" != --* ]]; then
  [[ "$IP" == *:* ]] || IP="$IP:5555"
  adb connect "$IP" >/dev/null
  ADB=(adb -s "$IP")
else
  MODE="$IP"
fi

echo "Reactivando launchers originales..."
DISABLED=$("${ADB[@]}" shell cat "$STATE_FILE" 2>/dev/null | tr -d '\r' || true)
for P in $DISABLED com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher; do
  if "${ADB[@]}" shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -qx "package:$P"; then
    echo "  - activando $P"
    "${ADB[@]}" shell pm enable "$P" >/dev/null
  fi
done
"${ADB[@]}" shell rm -f "$STATE_FILE" || true

# Quitar el rol de launcher a Inicio TV para que el sistema vuelva al original.
"${ADB[@]}" shell pm disable "$PKG/$PKG.HomeAlias" >/dev/null 2>&1 || true

# Preferir el launcher de Google: otras apps (p. ej. Orange TV) también se declaran HOME.
HOMES=$("${ADB[@]}" shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
  | tr -d '\r' | grep '/' | sed 's/^ *//' | grep -v -e "^$PKG/" -e FallbackHome -e '^com.android.provision' || true)
ORIGINAL=$(echo "$HOMES" | grep -E '^com\.google\.android\.(tvlauncher|apps\.tv\.launcherx|leanbacklauncher)/' | head -n1 || true)
[[ -n "$ORIGINAL" ]] || ORIGINAL=$(echo "$HOMES" | head -n1)
if [[ -n "$ORIGINAL" ]]; then
  echo "Launcher predeterminado: $ORIGINAL"
  "${ADB[@]}" shell cmd package set-home-activity "$ORIGINAL" >/dev/null 2>&1 || true
fi

if [[ "$MODE" == "--desinstalar" ]]; then
  echo "Desinstalando Inicio TV..."
  "${ADB[@]}" uninstall "$PKG" >/dev/null || true
fi

"${ADB[@]}" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Listo: la TV vuelve a usar su launcher original."
