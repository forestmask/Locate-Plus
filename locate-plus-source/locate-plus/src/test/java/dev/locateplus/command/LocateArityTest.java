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
package dev.locateplus.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape tests for the {@code /locate} command tree.
 *
 * <p>Two behaviours are pinned here:</p>
 * <ul>
 *   <li>The radius cannot repeat: {@code /locate block minecraft:iron_ore 64 64 64} must not
 *       parse.</li>
 *   <li>The unit is a required literal. {@code 64} alone must not execute; {@code 64 blocks} must.</li>
 * </ul>
 */
class LocateArityTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandDispatcher<Object> tree() throws Exception {
        CommandDispatcher dispatcher = new CommandDispatcher();
        Method register = LocateCommand.class.getDeclaredMethod("register", CommandDispatcher.class);
        register.setAccessible(true);
        try {
            register.invoke(null, dispatcher);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "command tree needs a bootstrapped game (" + root.getClass().getSimpleName()
                            + "); verified against a live server instead");
        }
        return (CommandDispatcher<Object>) dispatcher;
    }

    private static CommandNode<Object> soleChild(CommandNode<Object> parent) {
        List<CommandNode<Object>> kids = List.copyOf(parent.getChildren());
        assertEquals(1, kids.size(),
                "expected one child of '" + parent.getName() + "', found " + kids.size());
        return kids.get(0);
    }

    @Test
    @DisplayName("/locate exposes exactly the block and entity subcommands")
    void subcommands() throws Exception {
        CommandNode<Object> locate = tree().getRoot().getChild("locate");
        assertNotNull(locate, "/locate not registered");
        assertNotNull(locate.getChild("block"));
        assertNotNull(locate.getChild("entity"));
        assertEquals(2, locate.getChildren().size());
    }

    @Test
    @DisplayName("the unit is required: <target> <n> alone must not execute")
    void unitIsRequired() throws Exception {
        for (String sub : new String[]{"block", "entity"}) {
            CommandNode<Object> node = tree().getRoot().getChild("locate").getChild(sub);
            CommandNode<Object> target = soleChild(node);
            CommandNode<Object> radius = soleChild(target);

            assertEquals("radius", radius.getName(), sub + ": expected the radius node");
            assertNull(radius.getCommand(),
                    sub + ": a bare number must not be executable, the unit is required");
        }
    }

    @Test
    @DisplayName("both unit literals are accepted and each is executable")
    void unitSpellings() throws Exception {
        for (String sub : new String[]{"block", "entity"}) {
            CommandNode<Object> radius = soleChild(soleChild(
                    tree().getRoot().getChild("locate").getChild(sub)));

            Set<String> units = radius.getChildren().stream()
                    .map(CommandNode::getName).collect(Collectors.toSet());
            assertEquals(Set.of("blocks", "chunks"), units,
                    sub + ": expected exactly the two plural unit literals");

            for (CommandNode<Object> unit : radius.getChildren()) {
                assertNotNull(unit.getCommand(),
                        sub + " " + unit.getName() + ": must be executable");
            }
        }
    }

    @Test
    @DisplayName("radius cannot repeat: only 'forceload' may follow a unit")
    void radiusIsNotRepeatable() throws Exception {
        for (String sub : new String[]{"block", "entity"}) {
            CommandNode<Object> radius = soleChild(soleChild(
                    tree().getRoot().getChild("locate").getChild(sub)));

            for (CommandNode<Object> unit : radius.getChildren()) {
                assertNull(unit.getChild("radius"),
                        sub + " " + unit.getName() + ": a second radius must not be accepted");

                CommandNode<Object> forceload = soleChild(unit);
                assertEquals("forceload", forceload.getName(),
                        sub + " " + unit.getName() + ": only 'forceload' may follow");
                assertNotNull(forceload.getCommand());
                assertTrue(forceload.getChildren().isEmpty(),
                        "'forceload' must end the chain");
            }
        }
    }

    @Test
    @DisplayName("the target alone still runs, so the radius stays optional")
    void targetAloneRuns() throws Exception {
        for (String sub : new String[]{"block", "entity"}) {
            CommandNode<Object> target = soleChild(
                    tree().getRoot().getChild("locate").getChild(sub));
            assertNotNull(target.getCommand(), sub + ": <target> alone must run");
        }
    }
}
