package br.com.habittracker.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    // Equal work for an unknown email and a known email with a wrong password.
    private static final String DUMMY = hash("dummy-password-for-timing-only");

    private Passwords() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean verify(String password, String encoded) {
        try {
            String[] fields = (encoded == null ? DUMMY : encoded).split("\\$", -1);
            if (fields.length != 4 || !fields[0].equals("pbkdf2-sha256")) return false;
            int iterations = Integer.parseInt(fields[1]);
            if (iterations < 10_000 || iterations > 2_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(fields[2]);
            byte[] expected = Base64.getDecoder().decode(fields[3]);
            if (salt.length != 16 || expected.length != 32) return false;
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual) && encoded != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2 indisponível", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
