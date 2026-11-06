// SPDX-License-Identifier: GPL-3.0-only
// The real ring allocator over a synthetic mapped buffer: no GPU/driver needed.
#include "galaxy/render/gpu_upload_ring.h"

#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

namespace galaxy::render::vk {
struct Buffer { std::vector<std::byte> bytes; };
}

namespace galaxy::render::gpu {
UploadMemory create_upload_memory(Device*, std::size_t bytes, const char*) {
    UploadMemory out;
    auto buffer = std::make_shared<vk::Buffer>();
    buffer->bytes.resize(bytes);
    out.cpu = buffer->bytes.data();
    out.gpu = 0x100000000ull;
    out.buffer = std::move(buffer);
    return out;
}
}

namespace {
void require(bool value, const char* message) {
    if (!value) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        std::exit(1);
    }
}
}

int main() {
    using galaxy::render::UploadRing;
    using galaxy::render::gpu::Device;
    for (const std::size_t alignment : {16u, 64u, 256u}) {
        UploadRing ring;
        require(ring.initialize(reinterpret_cast<Device*>(1), "storage-alignment-test", 4096u, 3u, 16384u),
                "initialize synthetic mapped ring");
        for (unsigned slot = 0u; slot < 3u; ++slot) {
            ring.begin_frame(slot);
            // Six rows per draw matrix plus the existing float4 tail deliberately
            // leave the cursor at 16 (mod 64/256), matching the broken device case.
            const auto rows = ring.allocate(2u * 6u * 16u + 16u, 16u);
            const auto base = (rows.gpu - ring.gpu_base()) / 16u;
            const std::array<float, 4> sentinel{1.25f, 2.5f, 3.75f, 4.0f};
            std::memcpy(rows.cpu, sentinel.data(), sizeof(sentinel));
            const auto instances = ring.allocate(3u * 16u + 16u, alignment);
            require(instances.offset % alignment == 0u, "storage descriptor offset meets device alignment");
            require(instances.gpu - ring.gpu_base() == instances.offset, "GPU/descriptor offset agrees with allocation");
            auto* table = reinterpret_cast<std::uint32_t*>(instances.cpu);
            table[0] = static_cast<std::uint32_t>(base);
            table[4] = static_cast<std::uint32_t>(base + 6u);
            const auto* descriptor = reinterpret_cast<const std::uint32_t*>(ring.cpu_base() + instances.offset);
            require(descriptor[0] == base && descriptor[4] == base + 6u, "aligned table retains shader row indices");
            require(std::memcmp(ring.cpu_base() + descriptor[0] * 16u, sentinel.data(), sizeof(sentinel)) == 0,
                    "shader row lookup reaches original matrix after padding");
        }
    }
    std::puts("upload-ring storage alignment: 16/64/256, three frame slots, row indices OK");
}
