#!/bin/sh
# Downloads each catalog merchant's Play Store icon into res/drawable-nodpi/brand_<id>.webp, so known services show
# their real logo with no network request at runtime. Pairs come from core's BrandApps.kt; re-run after adding one.
set -eu
cd "$(dirname "$0")/.."
out=app/src/main/res/drawable-nodpi
mkdir -p "$out"
missing=0
grep -o '"[a-z0-9]*" to "[A-Za-z0-9_.]*"' core/src/main/kotlin/com/aasif/watchdog/core/merchant/BrandApps.kt |
  sed 's/"\([^"]*\)" to "\([^"]*\)"/\1 \2/' | while read -r id pkg; do
    url=$(curl -sL -A "Mozilla/5.0" "https://play.google.com/store/apps/details?id=$pkg&hl=en_IN" |
      grep -o '<meta property="og:image" content="[^"]*"' | head -1 | sed 's/.*content="//;s/"$//;s/=s[0-9a-z-]*$//')
    if [ -z "$url" ]; then echo "MISSING $id ($pkg)"; continue; fi
    curl -sL "$url=s144-rw" -o "$out/brand_$id.webp"
    echo "ok $id"
  done
