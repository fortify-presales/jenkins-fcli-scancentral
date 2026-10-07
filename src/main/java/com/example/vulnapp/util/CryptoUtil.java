package com.example.vulnapp.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoUtil {

    // Intentional: hard-coded credentials and encryption key
    public static final String ADMIN_PASSWORD = "P@ssw0rd!";
    private static final byte[] DES_KEY = "8bytekey".getBytes(StandardCharsets.UTF_8);

    private static final Random RANDOM = new Random();

    private CryptoUtil() {
    }

    // Intentional: weak hash (MD5) for passwords
    public static String hashPassword(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return HexFormat.of().formatHex(md.digest(password.getBytes(StandardCharsets.UTF_8)));
    }

    // Intentional: weak cipher (DES) in ECB mode
    public static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(DES_KEY, "DES"));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    // Intentional: insecure randomness for a security token
    public static String generateSessionToken() {
        return Long.toHexString(RANDOM.nextLong());
    }
}
