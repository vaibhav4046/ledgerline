package dev.ledgerline.stripe;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies Stripe's {@code Stripe-Signature: t=...,v1=...} header (HMAC-SHA256 over "t.payload"). */
final class WebhookSignature {
    static final long TOLERANCE_SECONDS = 300;

    private WebhookSignature() {
    }

    static boolean isValid(String header, byte[] payload, String secret, long nowEpochSeconds) {
        if (header == null || secret.isEmpty()) {
            return false;
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty() || Math.abs(nowEpochSeconds - timestamp) > TOLERANCE_SECONDS) {
            return false;
        }
        byte[] expected = sign(secret, timestamp, payload).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    static String sign(String secret, long timestamp, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
