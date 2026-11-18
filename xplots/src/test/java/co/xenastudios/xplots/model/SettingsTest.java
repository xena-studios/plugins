package co.xenastudios.xplots.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.xenastudios.xplots.config.XPConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsTest {

    @Test
    void checksAndNormalisesValues() {
        assertEquals("128", Settings.check("price", "128.0"));
        assertEquals("2.5", Settings.check("slot-price", "2.5"));
        assertEquals("1d12h", Settings.check("period", "36h"));
        assertEquals("25", Settings.check("abandon-refund", "25%"));
        assertEquals("false", Settings.check("reset", "FALSE"));
        assertThrows(IllegalArgumentException.class, () -> Settings.check("price", "-1"));
        assertThrows(IllegalArgumentException.class, () -> Settings.check("period", "10s"));
        assertThrows(IllegalArgumentException.class, () -> Settings.check("max-slots", "46"));
        assertThrows(IllegalArgumentException.class, () -> Settings.check("colour", "red"));
    }

    @Test
    void overridesApplyOverTheDefaults() {
        XPConfig.PlotSettings base = XPConfig.DEFAULTS.plots();
        Map<String, String> own = Settings.decode(Settings.encode(Map.of("price", "250", "reset", "false", "period", "1d")));
        XPConfig.PlotSettings applied = Settings.apply(base, own);
        assertEquals(250, applied.price());
        assertFalse(applied.reset());
        assertEquals(Duration.ofDays(1), applied.period());
        assertEquals(base.maxSlots(), applied.maxSlots());
    }

    @Test
    void maxSlotsIsNeverBelowStartingSlots() {
        XPConfig.PlotSettings applied = Settings.apply(XPConfig.DEFAULTS.plots(), Map.of("starting-slots", "5", "max-slots", "2"));
        assertEquals(5, applied.maxSlots());
    }
}
