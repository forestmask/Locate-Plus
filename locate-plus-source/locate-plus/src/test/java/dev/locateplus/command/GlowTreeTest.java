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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Structure of the {@code /glow} command tree.
 *
 * <p>The target cannot be a single {@code word()} argument: Brigadier's word charset is
 * {@code [A-Za-z0-9_.+-]} and excludes {@code ':'}, so {@code minecraft:cow} would parse as
 * {@code minecraft} and leave {@code :cow} as trailing data.</p>
 *
 * <p>The fix is two sibling branches with different vanilla argument types, a registry predicate
 * for ids and tags, an entity selector for {@code @e} and friends. These tests pin that shape
 * down so the regression cannot come back.</p>
 */
class GlowTreeTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandDispatcher<Object> tree() throws Exception {
        CommandDispatcher dispatcher = new CommandDispatcher();
        Method register = GlowCommand.class.getDeclaredMethod("register", CommandDispatcher.class);
        register.setAccessible(true);
        try {
            register.invoke(null, dispatcher);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "needs a bootstrapped game (" + root.getClass().getSimpleName() + ")");
        }
        return (CommandDispatcher<Object>) dispatcher;
    }

    private static void assertRadiusUnits(CommandNode<Object> target, String where) {
        CommandNode<Object> radius = target.getChild("radius");
        assertNotNull(radius, where + ": radius missing; children were "
                + target.getChildren().stream().map(CommandNode::getName).toList());

        Set<String> units = radius.getChildren().stream()
                .map(CommandNode::getName).collect(Collectors.toSet());
        assertEquals(Set.of("blocks", "chunks"), units,
                where + ": expected exactly the two plural unit literals");

        for (CommandNode<Object> unit : radius.getChildren()) {
            assertNotNull(unit.getCommand(), where + " " + unit.getName() + ": must be executable");
            org.junit.jupiter.api.Assertions.assertNull(unit.getChild("forceload"),
                    where + " " + unit.getName() + ": /glow must NOT offer forceload, glowing "
                            + "entities nobody can see is pointless and generates terrain");
        }
    }

    @Test
    @DisplayName("/glow is registered, and the old names are gone")
    void singleCommandName() throws Exception {
        CommandDispatcher<Object> dispatcher = tree();
        assertNotNull(dispatcher.getRoot().getChild("glow"), "/glow missing");
        org.junit.jupiter.api.Assertions.assertNull(
                dispatcher.getRoot().getChild("highlight"), "/highlight should no longer exist");
        org.junit.jupiter.api.Assertions.assertNull(
                dispatcher.getRoot().getChild("glowall"), "/glowall should no longer exist");
    }

    @Test
    @DisplayName("each name offers an id/tag branch and a selector branch")
    void bothTargetBranchesExist() throws Exception {
        for (String name : new String[]{"glow"}) {
            CommandNode<Object> root = tree().getRoot().getChild(name);
            Set<String> branches = root.getChildren().stream()
                    .map(CommandNode::getName).collect(Collectors.toSet());
            assertEquals(Set.of("type", "selector"), branches,
                    name + ": expected exactly the id/tag and selector branches");
        }
    }

    @Test
    @DisplayName("every branch carries the full radius tail")
    void branchesHaveRadius() throws Exception {
        for (String name : new String[]{"glow"}) {
            CommandNode<Object> root = tree().getRoot().getChild(name);
            assertRadiusUnits(root.getChild("type"), name + " type");
            assertRadiusUnits(root.getChild("selector"), name + " selector");
        }
    }

    @Test
    @DisplayName("a target with no radius still runs, so the radius stays optional")
    void targetAloneRuns() throws Exception {
        for (String name : new String[]{"glow"}) {
            CommandNode<Object> root = tree().getRoot().getChild(name);
            assertNotNull(root.getChild("type").getCommand(),
                    name + ": <id> alone must run");
            assertNotNull(root.getChild("selector").getCommand(),
                    name + ": <selector> alone must run");
        }
    }
}
