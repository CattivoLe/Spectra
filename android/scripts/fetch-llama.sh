#!/usr/bin/env bash
# Скачивает исходники llama.cpp той же версии, что у iOS (ios/Tools/build-llama.sh), в модуль движка.
# Android собирает их сам через CMake: готовых сборок под Android у проекта нет.
# bash, а не zsh: скрипт запускает и CI на Linux, где zsh нет.
set -euo pipefail

tag="b12359"
target="$(cd "$(dirname "$0")/.." && pwd)/engine/src/main/cpp/llama.cpp"
rm -rf "$target"
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$tag" https://github.com/ggml-org/llama.cpp "$target"
rm -rf "$target/.git"
echo "Готово: $target"
