/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.util;

import com.mojang.brigadier.StringReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tokenising is the first place {@code /locate entity} can go wrong: if the token is cut short,
 * Brigadier rejects the leftover text and the player sees a parse error rather than a search.
 */
class TargetTokenReaderTest {

    private static String readToken(String input) {
        return TargetTokenReader.read(new StringReader(input));
    }

    private static String remainder(String input) {
        StringReader reader = new StringReader(input);
        TargetTokenReader.read(reader);
        return reader.getRemaining();
    }

    @Test
    @DisplayName("plain entity id is read whole, including the namespace colon")
    void readsNamespacedId() {
        assertEquals("minecraft:zombie", readToken("minecraft:zombie"));
    }

    @Test
    @DisplayName("bare id stops at the first space so the radius stays separate")
    void stopsAtSpace() {
        assertEquals("zombie", readToken("zombie 64"));
        assertEquals(" 64", remainder("zombie 64"));
    }

    @Test
    @DisplayName("simple selector")
    void readsSimpleSelector() {
        assertEquals("@e", readToken("@e"));
        assertEquals("@a", readToken("@a 128 forceload"));
    }

    @Test
    @DisplayName("selector with brackets is one token")
    void readsBracketedSelector() {
        assertEquals("@e[type=minecraft:zombie]", readToken("@e[type=minecraft:zombie]"));
    }

    @Test
    @DisplayName("spaces inside brackets do not end the token")
    void readsSelectorContainingSpaces() {
        String input = "@e[type=minecraft:zombie, distance=..5] 64";
        assertEquals("@e[type=minecraft:zombie, distance=..5]", readToken(input));
        assertEquals(" 64", remainder(input));
    }

    @Test
    @DisplayName("nested braces in an nbt predicate are balanced correctly")
    void readsNestedNbtSelector() {
        String selector = "@e[nbt={Inventory:[{id:\"minecraft:stone\"}]}]";
        assertEquals(selector, readToken(selector + " 32"));
    }

    @Test
    @DisplayName("a closing bracket inside quotes does not terminate the selector")
    void ignoresBracketsInsideQuotes() {
        String selector = "@e[name=\"weird]name\"]";
        assertEquals(selector, readToken(selector));
    }

    @Test
    @DisplayName("trailing arguments survive a complex selector")
    void leavesTrailingArguments() {
        String input = "@e[type=minecraft:item,limit=5,sort=nearest] 128 forceload";
        assertEquals("@e[type=minecraft:item,limit=5,sort=nearest]", readToken(input));
        assertEquals(" 128 forceload", remainder(input));
    }

    @Test
    @DisplayName("tag targets are read whole")
    void readsTag() {
        assertEquals("#minecraft:skeletons", readToken("#minecraft:skeletons 64"));
    }

    @Test
    @DisplayName("quoted player names keep their spaces")
    void readsQuotedName() {
        assertEquals("\"Some Player\"", readToken("\"Some Player\" 64"));
    }

    @Test
    @DisplayName("wildcard")
    void readsWildcard() {
        assertEquals("*", readToken("*"));
    }

    @Test
    @DisplayName("an unterminated bracket consumes the rest rather than looping forever")
    void handlesUnterminatedBracket() {
        assertEquals("@e[type=zombie", readToken("@e[type=zombie"));
    }
}
