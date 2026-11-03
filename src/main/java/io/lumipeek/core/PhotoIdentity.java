package io.lumipeek.core;

import java.nio.ByteBuffer;
import java.security.*;
import java.util.UUID;

/** Session-salted content identity. Keep only in memory; never log or persist. */
public final class PhotoIdentity {
    private final byte[] salt = new byte[32];
    public PhotoIdentity() { new SecureRandom().nextBytes(salt); }
    public UUID identify(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > PhotoFolder.MAX_BYTES) throw new IllegalArgumentException("image size");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            ByteBuffer hash = ByteBuffer.wrap(digest.digest(bytes));
            return new UUID(hash.getLong(), hash.getLong());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
