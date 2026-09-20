package dev.pinatafest.vote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteInputTest {

    @Test
    void normalAndBedrockNamesAreAccepted() {
        assertTrue(VoteService.validName("Steve_1"));
        assertTrue(VoteService.validName(".BedrockPlayer"));
    }

    @Test
    void namesThatCouldBreakACommandAreRefused() {
        assertFalse(VoteService.validName("Steve give @a diamond"));
        assertFalse(VoteService.validName("a;op b"));
        assertFalse(VoteService.validName("<red>x"));
        assertFalse(VoteService.validName(""));
        assertFalse(VoteService.validName(null));
        assertFalse(VoteService.validName("x".repeat(33)));
    }

    @Test
    void serviceNamesKeepOnlyPlainCharacters() {
        assertEquals("vote.example.com", VoteService.cleanService("vote.example.com"));
        assertEquals("sitegive", VoteService.cleanService("site; give"));
        assertEquals("unknown", VoteService.cleanService(";;"));
        assertEquals("unknown", VoteService.cleanService(null));
    }
}
