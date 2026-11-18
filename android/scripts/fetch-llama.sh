#!/bin/zsh
# Скачивает исходники llama.cpp той же версии, что у iOS (scripts/fetch-llama.sh в корне), в модуль движка.
# Android собирает их сам через CMake: готовых сборок под Android у проекта нет.
set -euo pipefail

tag="b12359"
target="${0:A:h:h}/engine/src/main/cpp/llama.cpp"
rm -rf "$target"
git clone --quiet --depth 1 --branch "$tag" https://github.com/ggml-org/llama.cpp "$target"
rm -rf "$target/.git"
echo "Готово: $target"
