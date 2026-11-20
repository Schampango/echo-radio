#!/bin/bash
# Baut die Echo-Radio-App auf Linux Mint / Ubuntu (als root ausfuehren).
# Beim ersten Mal werden Java 17 und das Android SDK (ca. 1 GB) installiert.
set -e
cd "$(dirname "$0")"

SDK=/opt/android-sdk
TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip

echo "== Java 17"
if [ ! -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    apt-get update
    apt-get install -y openjdk-17-jdk-headless unzip wget
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo "== Android SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    mkdir -p "$SDK/cmdline-tools"
    wget -q --show-progress -O "/tmp/$TOOLS_ZIP" "https://dl.google.com/android/repository/$TOOLS_ZIP"
    rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
    unzip -q -o "/tmp/$TOOLS_ZIP" -d "$SDK/cmdline-tools"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0"
fi
echo "sdk.dir=$SDK" > local.properties

echo "== App bauen"
chmod +x gradlew
./gradlew --no-daemon assembleDebug

# Ergebnis in den Projektordner: mit Version und als echo-radio.apk (fuer gleichbleibende Befehle)
BUILT=$(ls -t app/build/outputs/apk/debug/echo-radio-*.apk | head -n 1)
cp "$BUILT" .
cp "$BUILT" echo-radio.apk
echo
echo "Fertig: $(pwd)/$(basename "$BUILT")"
echo "  und:  $(pwd)/echo-radio.apk"
echo
echo "Installieren: adb install -r echo-radio.apk"
