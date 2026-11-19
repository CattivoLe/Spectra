// The KV state of a prompt's system part, saved to a file and loaded back (see PromptCache.kt).
// Same layout as iOS (PromptCache.swift): "SPKV", format version, token count, tokens (int32), payload size (uint64),
// then llama.cpp's own state of sequence 0 from llama_state_seq_get_data.
// llama_state_seq_load_file is not used: on a truncated file it aborts the process (GGML_ASSERT) instead of failing,
// while llama_state_seq_set_data reads from a bounded buffer and returns 0.
#pragma once

#include <cstdint>
#include <cstring>
#include <vector>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "llama.h"

namespace prompt_state {

constexpr uint32_t kMagic = 0x564B5053;  // bytes "SPKV" little-endian
constexpr uint32_t kVersion = 1;

inline std::vector<uint8_t> header(const llama_token * tokens, size_t count, uint64_t payload) {
    std::vector<uint8_t> out(4 + 4 + 4 + count * sizeof(llama_token) + 8);
    uint8_t * p = out.data();
    const uint32_t n = static_cast<uint32_t>(count);
    std::memcpy(p, &kMagic, 4); p += 4;
    std::memcpy(p, &kVersion, 4); p += 4;
    std::memcpy(p, &n, 4); p += 4;
    std::memcpy(p, tokens, count * sizeof(llama_token)); p += count * sizeof(llama_token);
    std::memcpy(p, &payload, 8);
    return out;
}

/// Writes the state of sequence 0, which must hold exactly `tokens`, straight into a memory-mapped file.
inline bool save(llama_context * ctx, const char * path, const llama_token * tokens, size_t count) {
    const size_t size = llama_state_seq_get_size(ctx, 0);
    if (size == 0) return false;
    const std::vector<uint8_t> head = header(tokens, count, size);
    const size_t total = head.size() + size;
    const int fd = open(path, O_CREAT | O_TRUNC | O_RDWR, 0644);
    if (fd < 0) return false;
    bool ok = false;
    if (ftruncate(fd, static_cast<off_t>(total)) == 0) {
        void * mapped = mmap(nullptr, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (mapped != MAP_FAILED) {
            auto * bytes = static_cast<uint8_t *>(mapped);
            std::memcpy(bytes, head.data(), head.size());
            ok = llama_state_seq_get_data(ctx, bytes + head.size(), size, 0) == size && msync(mapped, total, MS_SYNC) == 0;
            munmap(mapped, total);
        }
    }
    close(fd);
    return ok;
}

/// Loads a saved state into sequence 0 when the file is a complete state of exactly `tokens`. False otherwise,
/// with the cache possibly half-written: the caller clears it.
inline bool load(llama_context * ctx, const char * path, const llama_token * tokens, size_t count) {
    const int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    struct stat st {};
    bool ok = false;
    const std::vector<uint8_t> expected = header(tokens, count, 0);
    if (fstat(fd, &st) == 0 && static_cast<size_t>(st.st_size) > expected.size()) {
        const size_t total = static_cast<size_t>(st.st_size);
        void * mapped = mmap(nullptr, total, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapped != MAP_FAILED) {
            const auto * bytes = static_cast<const uint8_t *>(mapped);
            const size_t fixed = expected.size() - 8;
            uint64_t payload = 0;
            std::memcpy(&payload, bytes + fixed, 8);
            if (std::memcmp(bytes, expected.data(), fixed) == 0 && payload > 0 && payload == total - expected.size()) {
                ok = llama_state_seq_set_data(ctx, bytes + expected.size(), payload, 0) == payload;
            }
            munmap(mapped, total);
        }
    }
    close(fd);
    return ok;
}

}  // namespace prompt_state
