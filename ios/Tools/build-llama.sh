#!/bin/zsh
# Builds llama.cpp into ios/Packages/LocalChatEngine/Vendor/llama.xcframework with iPhone AND
# simulator slices. The prebuilt release zip has no simulator slice, which would break
# `xcodebuild test` for Spectra. Takes ~15 minutes; the result is git-ignored.
set -euo pipefail

tag="b12359"
root="${0:A:h:h}/Packages/LocalChatEngine/Vendor"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone --quiet --depth 1 --branch "$tag" https://github.com/ggml-org/llama.cpp "$work/llama.cpp"
(cd "$work/llama.cpp" && ./build-xcframework.sh ios-sim ios-device)

mkdir -p "$root"
rm -rf "$root/llama.xcframework"
mv "$work/llama.cpp/build-apple/llama.xcframework" "$root/llama.xcframework"
echo "Done: $root/llama.xcframework"
