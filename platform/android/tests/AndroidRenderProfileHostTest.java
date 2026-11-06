// SPDX-License-Identifier: GPL-3.0-only
package dev.nebula.galaxy;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class AndroidRenderProfileHostTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        File first = root.resolve("new/data/config.ini").toFile();
        check(AndroidRenderProfile.ensureFirstConfig(first), "first launch creates the profile");
        byte[] initial = Files.readAllBytes(first.toPath());
        Properties settings = new Properties();
        settings.load(new ByteArrayInputStream(initial));
        check(settings.size() == 8, "exactly the eight measured profile settings");
        for (String entry : List.of("max_fps=60", "vsync_interval=1", "spatial_quality=quality", "upscaler=fsr",
                "hud_output_resolution=1", "msaa_samples=1", "aa=off", "sharpness=0")) {
            String[] pair = entry.split("=", 2);
            check(pair[1].equals(settings.getProperty(pair[0])), "first-run value: " + entry);
        }
        check(!AndroidRenderProfile.ensureFirstConfig(first), "second launch does not reset the profile");
        check(Arrays.equals(initial, Files.readAllBytes(first.toPath())), "second launch preserves bytes");

        byte[][] existing = {
            "max_fps=0\nupscaler=bilinear\nspatial_quality=manual\ncustom_key=kept\n".getBytes(StandardCharsets.UTF_8),
            new byte[0],
            new byte[] {(byte) 0xff, 0, (byte) 0xfe, 42},
        };
        for (int i = 0; i < existing.length; ++i) {
            File custom = root.resolve("existing-" + i + ".ini").toFile();
            Files.write(custom.toPath(), existing[i]);
            var modified = Files.getLastModifiedTime(custom.toPath());
            check(!AndroidRenderProfile.ensureFirstConfig(custom), "existing custom/empty/invalid config is not replaced");
            check(Arrays.equals(existing[i], Files.readAllBytes(custom.toPath())), "existing bytes stay intact");
            check(modified.equals(Files.getLastModifiedTime(custom.toPath())), "existing timestamp stays intact");
        }
        File directory = root.resolve("directory.ini").toFile();
        Files.createDirectory(directory.toPath());
        check(!AndroidRenderProfile.ensureFirstConfig(directory) && directory.isDirectory(), "invalid existing directory stays");

        File concurrent = root.resolve("concurrent.ini").toFile();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 8; ++i) jobs.add(() -> AndroidRenderProfile.ensureFirstConfig(concurrent));
            int created = 0;
            for (Future<Boolean> result : pool.invokeAll(jobs)) if (result.get()) ++created;
            check(created == 1, "concurrent launch creates the file exactly once");
            check(Arrays.equals(initial, Files.readAllBytes(concurrent.toPath())), "concurrent launch retains complete profile");
        } finally {
            pool.shutdown();
        }
        System.out.println("PASS: first-run profile, existing custom/empty/invalid files, repeated and concurrent launch");
    }
}
