#!/usr/bin/env bash
# Creates the local signing key used for sideload builds, if it does not exist.
#
# The key is deliberately NOT in the repository. It is a signing key, and
# publishing one lets anyone produce a package Android will accept as an update
# to yours. It costs nothing to generate your own, so generate your own.
#
# Keep the file. Losing it means a new key, and Android refuses to update an
# installed app whose signer changed: you would have to uninstall and reinstall,
# losing the app's data and sign-in.
set -euo pipefail
cd "$(dirname "$0")/.."

KS="app/sideload.keystore"
[[ -f "$KS" ]] && exit 0

if [[ -z "${JAVA_HOME:-}" && -f ./env.sh ]]; then . ./env.sh; fi
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

echo "==> Generating $KS (one time)"
"$KEYTOOL" -genkeypair -v \
  -keystore "$KS" \
  -alias yourtoba \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass yourtoba -keypass yourtoba \
  -dname "CN=YourTOBA, OU=Sideload, O=YourTOBA, L=, ST=, C=" >/dev/null
echo "    done. Keep this file; it is gitignored on purpose."
