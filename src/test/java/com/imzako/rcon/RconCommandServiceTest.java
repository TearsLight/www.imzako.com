package com.imzako.rcon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RconCommandServiceTest {
    @Test
    void tokenizesQuotedCommandArguments() {
        assertThat(RconCommandService.tokenize("imzako new \"博客 名\" --tags 'Java 后端' --publish"))
                .containsExactly("imzako", "new", "博客 名", "--tags", "Java 后端", "--publish");
        assertThat(RconCommandService.tokenize("ban 1000001 24 \"刷屏与骚扰\""))
                .containsExactly("ban", "1000001", "24", "刷屏与骚扰");
    }

    @Test
    void rejectsAnUnclosedQuote() {
        assertThatThrownBy(() -> RconCommandService.tokenize("new \"unfinished"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("引号");
    }
}
