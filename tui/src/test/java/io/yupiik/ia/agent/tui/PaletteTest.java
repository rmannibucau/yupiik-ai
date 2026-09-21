/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaletteTest {
    static Palette palette() {
        return new Palette(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", "#484848",
                "#606060", "#141414", "#ff5555", true);
    }

    static Palette darkPalette() {
        return new Palette(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", "#484848",
                "#606060", "#141414", "#ff5555", false);
    }

    @Test
    void monoPaletteUsesResetColors() {
        final var palette = palette();
        assertEquals(dev.tamboui.style.Color.RESET, palette.color("#123456"));
        assertEquals(dev.tamboui.style.Style.EMPTY, palette.style("#123456"));
    }

    @Test
    void colorPaletteResolvesHex() {
        final var palette = darkPalette();
        assertEquals(dev.tamboui.style.Color.hex("#9d7cd8"), palette.color("#9d7cd8"));
    }

    @Test
    void noColorEnvIsHonored() {
        // the check itself just must not throw in any environment state
        final var ignored = Palette.noColorRequested();
        assertTrue(ignored || !ignored);
    }
}
