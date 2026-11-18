package co.xenastudios.xbans;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.xenastudios.xbans.Punisher.Replacing;
import co.xenastudios.xbans.api.Punishment;
import co.xenastudios.xbans.api.PunishmentType;
import co.xenastudios.xbans.model.Entry;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The replacement rule, case by case, as DECISIONS.md and the commands page list it: what a
 * new ban or mute does to one already in force on the same scope. Mutes and bans never meet
 * (they're kept apart), which XBansTest checks on a server.
 */
class ReplacingTest {

    private static final UUID STEVE = UUID.randomUUID();
    private static final String IP = "203.0.113.9";
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant DAY = NOW.plusSeconds(86_400);
    private static final Instant TWO_DAYS = NOW.plusSeconds(2 * 86_400);

    private static Entry old(String ip, Instant expires) {
        return new Entry(new Punishment(1, PunishmentType.BAN, STEVE, "Steve", ip != null, "r", null, "Console",
            NOW.minusSeconds(60), expires, "*", false, null, null), ip);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
            // case, old (IP, ends), new (IP, ends), the sender may lift the old one, outcome
            Arguments.of("same length, permanent: a new reason", null, null, null, null, true, Replacing.REPLACE),
            Arguments.of("same length, ending together", null, DAY, null, DAY, true, Replacing.REPLACE),
            Arguments.of("longer", null, DAY, null, null, true, Replacing.REPLACE),
            Arguments.of("shorter", null, null, null, DAY, true, Replacing.REFUSE_LONGER),
            Arguments.of("IP over the account's, as long", null, DAY, IP, DAY, true, Replacing.REPLACE),
            Arguments.of("IP over the account's, shorter", null, TWO_DAYS, IP, DAY, true, Replacing.BOTH),
            Arguments.of("account over the IP's, as long", IP, DAY, null, DAY, true, Replacing.REFUSE_LONGER),
            Arguments.of("account over the IP's, longer", IP, DAY, null, TWO_DAYS, true, Replacing.BOTH),
            Arguments.of("a senior's, same length", null, null, null, null, false, Replacing.REFUSE_OUTRANKED),
            Arguments.of("a senior's, longer", null, DAY, null, null, false, Replacing.BOTH),
            Arguments.of("a senior's, shorter", null, null, null, DAY, false, Replacing.REFUSE_OUTRANKED),
            Arguments.of("a senior's, by IP and longer", null, DAY, IP, null, false, Replacing.BOTH),
            Arguments.of("a senior's IP ban, by account", IP, null, null, DAY, false, Replacing.REFUSE_OUTRANKED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theRule(String name, String oldIp, Instant oldEnds, String newIp, Instant newEnds, boolean mayLift,
                 Replacing expected) {
        assertEquals(expected, Punisher.replacing(old(oldIp, oldEnds), STEVE, newIp, newEnds, mayLift), name);
    }
}
