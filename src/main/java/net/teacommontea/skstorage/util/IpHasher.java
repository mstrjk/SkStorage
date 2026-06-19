package net.teacommontea.skstorage.util;

import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class IpHasher {

    private static final String ALGO = "HmacSHA256";

    private final boolean enabled;
    @Nullable private final SecretKeySpec key;

    public IpHasher(Path secretPath, boolean enabled) throws IOException {
        this.enabled = enabled;
        if (!enabled) {
            this.key = null;
            return;
        }
        byte[] secret = readOrCreateSecret(secretPath);
        this.key = new SecretKeySpec(secret, ALGO);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public String hash(String rawIp) {
        if (!enabled || key == null) return null;
        try {
            String normalized = normalize(rawIp);
            Mac mac = Mac.getInstance(ALGO);
            mac.init(key);
            byte[] result = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return null;
        }
    }

    private String normalize(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.getHostAddress();
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    private byte[] readOrCreateSecret(Path path) throws IOException {
        if (Files.exists(path)) {
            return Files.readAllBytes(path);
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        Files.createDirectories(path.getParent());
        Files.write(path, fresh);
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException ignored) {

        }
        return fresh;
    }
}
