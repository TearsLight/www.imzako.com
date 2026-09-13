package com.imzako.auth;

import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws the login captcha with the JDK image API, so no client library or third
 * party service is involved and nothing about the answer reaches the browser.
 * Challenges are single use and short lived.
 */
@Service
public class CaptchaService {
    /** Letters and digits that stay distinguishable once the image is distorted. */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 4;
    private static final int WIDTH = 150;
    private static final int HEIGHT = 52;
    private static final int MAX_CHALLENGES = 10_000;
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Stored> challenges = new LinkedHashMap<>(128, 0.75f, true);

    public record Challenge(String id, BufferedImage image, int width, int height) {}

    private record Stored(String answer, Instant expiresAt, boolean used) {}

    public synchronized Challenge create() {
        purgeExpired();
        if (challenges.size() >= MAX_CHALLENGES) {
            var eldest = challenges.keySet().iterator();
            if (eldest.hasNext()) challenges.remove(eldest.next());
        }
        String answer = randomWord();
        String id = randomId();
        challenges.put(id, new Stored(answer, Instant.now().plus(TTL), false));
        return new Challenge(id, render(answer), WIDTH, HEIGHT);
    }

    /**
     * Checks an answer and consumes the challenge. Returns false for unknown,
     * expired, reused or incorrect answers without distinguishing between them.
     */
    public synchronized boolean consume(String id, String submitted) {
        if (id == null || id.isBlank()) return false;
        Stored stored = challenges.get(id);
        if (stored == null) return false;
        if (stored.used() || stored.expiresAt().isBefore(Instant.now())) {
            challenges.remove(id);
            return false;
        }
        // Mark used before comparing so a replay of the same id cannot succeed.
        challenges.put(id, new Stored(stored.answer(), stored.expiresAt(), true));
        challenges.remove(id);
        String value = submitted == null ? "" : submitted.trim().toUpperCase(Locale.ROOT);
        return !value.isEmpty() && value.equals(stored.answer());
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String randomWord() {
        StringBuilder word = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) word.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return word.toString();
    }

    private String randomId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder(32);
        for (byte b : bytes) id.append(String.format("%02x", b));
        return id.toString();
    }

    /** Draws the answer with jitter, rotation and noise so a bot cannot read it trivially. */
    BufferedImage render(String answer) {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF9, 0xF8, 0xF5));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // Interference lines drawn under the glyphs.
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 7; i++) {
                g.setColor(randomInk(140));
                g.drawLine(
                        RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT),
                        RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT));
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            int step = (WIDTH - 24) / LENGTH;
            for (int i = 0; i < answer.length(); i++) {
                var original = g.getTransform();
                double angle = Math.toRadians(RANDOM.nextInt(45) - 22);
                int x = 14 + i * step;
                int y = 36 + RANDOM.nextInt(9) - 4;
                g.rotate(angle, x, y);
                g.setColor(randomInk(90));
                g.drawString(String.valueOf(answer.charAt(i)), x, y);
                g.setTransform(original);
            }

            // Speckle on top of the glyphs.
            for (int i = 0; i < 220; i++) {
                g.setColor(randomInk(170));
                int x = RANDOM.nextInt(WIDTH);
                int y = RANDOM.nextInt(HEIGHT);
                g.fillRect(x, y, 1, 1);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private Color randomInk(int floor) {
        int range = Math.max(1, 255 - floor);
        return new Color(
                floor + RANDOM.nextInt(range),
                floor + RANDOM.nextInt(range),
                floor + RANDOM.nextInt(range));
    }
}
