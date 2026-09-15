package dev.reny.optimization.benchmark;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Deterministic hash of the profile configuration and user-facing options. */
public final class BenchmarkConfigHasher {

    private static final int BUFFER_SIZE = 8192;

    private BenchmarkConfigHasher() {}

    public static String hashProfile(File profileRoot) {
        if (profileRoot == null) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<HashedFile> files = new ArrayList<HashedFile>();
            collect(new File(profileRoot, "config"), profileRoot, files);
            collect(new File(profileRoot, "options.txt"), profileRoot, files);
            Collections.sort(files, new Comparator<HashedFile>() {

                @Override
                public int compare(HashedFile left, HashedFile right) {
                    return left.relativePath.compareTo(right.relativePath);
                }
            });
            byte[] buffer = new byte[BUFFER_SIZE];
            for (HashedFile file : files) {
                digest.update(file.relativePath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                InputStream stream = new FileInputStream(file.file);
                try {
                    int read;
                    while ((read = stream.read(buffer)) >= 0) {
                        if (read > 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                } finally {
                    stream.close();
                }
                digest.update((byte) '\n');
            }
            return "sha256:" + hex(digest.digest());
        } catch (IOException exception) {
            return "unknown";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void collect(File file, File profileRoot, List<HashedFile> result) throws IOException {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Unable to read configuration directory: " + file);
            }
            for (File child : children) {
                collect(child, profileRoot, result);
            }
        } else if (file.isFile()) {
            String root = profileRoot.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (!path.startsWith(root + File.separator) && !path.equals(root)) {
                throw new IOException("Configuration file escaped profile root: " + file);
            }
            result.add(
                new HashedFile(
                    file,
                    path.substring(root.length() + 1)
                        .replace(File.separatorChar, '/')));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", Integer.valueOf(value & 0xff)));
        }
        return result.toString();
    }

    private static final class HashedFile {

        private final File file;
        private final String relativePath;

        private HashedFile(File file, String relativePath) {
            this.file = file;
            this.relativePath = relativePath;
        }
    }
}
