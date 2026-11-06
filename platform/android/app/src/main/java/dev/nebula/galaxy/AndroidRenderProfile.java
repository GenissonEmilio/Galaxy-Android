// SPDX-License-Identifier: GPL-3.0-only
package dev.nebula.galaxy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** First-run graphics defaults. Existing files, including unreadable or invalid ones, belong to the player. */
final class AndroidRenderProfile {
    private AndroidRenderProfile() {}

    static boolean ensureFirstConfig(File config) throws IOException {
        if (config.exists()) return false;
        File parent = config.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        String defaults = "max_fps=60\nvsync_interval=1\nspatial_quality=quality\n"
                + "upscaler=fsr\nhud_output_resolution=1\nmsaa_samples=1\naa=off\nsharpness=0\n";
        try {
            // CREATE_NEW is exclusive: another process creating a config between
            // the existence check and this write can never have its settings replaced.
            Files.write(config.toPath(), defaults.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException existing) {
            return false;
        }
    }
}
