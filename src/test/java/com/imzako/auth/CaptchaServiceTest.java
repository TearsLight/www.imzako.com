package com.imzako.auth;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CaptchaServiceTest {
    private final CaptchaService service = new CaptchaService();

    @Test
    void rendersAPngChallengeWithAStableSize() throws Exception {
        CaptchaService.Challenge challenge = service.create();

        assertThat(challenge.id()).hasSize(32).matches("[0-9a-f]+");
        assertThat(challenge.image().getWidth()).isEqualTo(150);
        assertThat(challenge.image().getHeight()).isEqualTo(52);

        var buffer = new ByteArrayOutputStream();
        ImageIO.write(challenge.image(), "png", buffer);
        byte[] png = buffer.toByteArray();
        // PNG magic number, so the browser really receives an image it can decode.
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3)).isEqualTo("PNG");
    }

    /**
     * Guards the highest-risk failure mode: if the glyphs were never painted the
     * challenge would be unsolvable, and a blank image still encodes as valid PNG.
     * Compared against a render of an empty answer so the assertion does not depend
     * on the absolute colour space {@code getRGB} reports.
     */
    @Test
    void paintingTheWordChangesManyPixels() {
        BufferedImage withWord = service.render("ABCD");
        BufferedImage withoutWord = service.render("");

        assertThat(differingPixels(withWord, withoutWord)).isGreaterThan(200);
    }

    @Test
    void differentWordsProduceDifferentImages() {
        assertThat(differingPixels(service.render("ABCD"), service.render("WXYZ"))).isGreaterThan(200);
    }

    @Test
    void challengesAreUniquePerCall() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) ids.add(service.create().id());
        assertThat(ids).hasSize(50);
    }

    @Test
    void rejectsUnknownBlankAndReusedChallenges() {
        CaptchaService.Challenge challenge = service.create();

        assertThat(service.consume(null, "ABCD")).isFalse();
        assertThat(service.consume("", "ABCD")).isFalse();
        assertThat(service.consume("does-not-exist", "ABCD")).isFalse();
        assertThat(service.consume(challenge.id(), null)).isFalse();
        assertThat(service.consume(challenge.id(), "")).isFalse();
        assertThat(service.consume(challenge.id(), "WRONG")).isFalse();
        // A guess burns the challenge, so it cannot be retried with another answer.
        assertThat(service.consume(challenge.id(), "ABCD")).isFalse();
    }

    private int differingPixels(BufferedImage first, BufferedImage second) {
        int differing = 0;
        for (int x = 0; x < first.getWidth(); x++) {
            for (int y = 0; y < first.getHeight(); y++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) differing++;
            }
        }
        return differing;
    }
}
