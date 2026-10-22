package co.xenastudios.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TextTest {

    @AfterEach
    void resetTheme() {
        Message.theme(Theme.DEFAULTS);
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void stylesAreTagsOrBareNames() {
        TextStyle tags = TextStyle.parse("<#B48CFF><bold>");
        assertEquals(TextColor.fromHexString("#B48CFF"), tags.style().color());
        assertTrue(tags.style().hasDecoration(TextDecoration.BOLD));
        TextStyle bare = TextStyle.parse("gold italic");
        assertEquals(NamedTextColor.GOLD, bare.style().color());
        assertTrue(bare.style().hasDecoration(TextDecoration.ITALIC));
        assertEquals(TextStyle.NONE, TextStyle.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> TextStyle.parse("<notacolour>"));
        assertThrows(IllegalArgumentException.class, () -> TextStyle.parse("purpleish"));
        assertThrows(IllegalArgumentException.class, () -> TextStyle.parse("<gradient:#B48CFF:#5EE08A>"), "a gradient can't be one style");
        assertThrows(IllegalArgumentException.class, () -> TextStyle.parse("<rainbow>"));
        assertThrows(IllegalArgumentException.class, () -> TextStyle.parse("<click:run_command:/spawn>"));
    }

    @Test
    void soundsAreANameWithAnOptionalVolumeAndPitch() {
        assertTrue(SoundEffect.parse("").isNone());
        assertEquals("block.anvil.use 0.5 1.2", SoundEffect.parse("block.anvil.use 0.5 1.2").toString());
        assertFalse(SoundEffect.parse("minecraft:block.barrel.open").isNone());
        assertThrows(IllegalArgumentException.class, () -> SoundEffect.parse("Not A Sound!"));
        assertThrows(IllegalArgumentException.class, () -> SoundEffect.parse("block.anvil.use loud"));
        assertThrows(IllegalArgumentException.class, () -> SoundEffect.parse("block.anvil.use 1 9"));
    }

    @Test
    void messagesUseTheThemesTagsAndBaseColour() {
        Component sent = Message.of("<prefix>Hi <highlight><player></highlight>.").chat(Placeholders.text("player", "Steve"));
        assertEquals("» Hi Steve.", plain(sent));
        assertEquals(NamedTextColor.GRAY, sent.color(), "the base colour wraps the message");

        Message.theme(new Theme("[S] ", TextStyle.parse("white"), TextStyle.parse("gold"), TextStyle.NONE, TextStyle.NONE));
        Component restyled = Message.of("<prefix><highlight>x").chat();
        assertEquals("[S] x", plain(restyled));
        assertEquals(NamedTextColor.WHITE, restyled.color());
    }

    @Test
    void aThemeChangeReachesMessagesParsedBefore() {
        Message message = Message.of("<prefix>x");
        assertEquals("» x", plain(message.render()));
        Message.theme(new Theme("", TextStyle.NONE, TextStyle.NONE, TextStyle.NONE, TextStyle.NONE));
        assertEquals("x", plain(message.render()));
    }
}
