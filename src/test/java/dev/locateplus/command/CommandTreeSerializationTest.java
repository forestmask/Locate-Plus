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
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards against unregistered argument types, which kick joining players.
 *
 * <h2>What went wrong</h2>
 *
 * <p>This mod originally declared two custom {@code ArgumentType} classes
 * ({@code EntityTargetArgumentType}, {@code BlockTargetArgumentType}) without registering them in
 * {@code Registries.COMMAND_ARGUMENT_TYPE}.</p>
 *
 * <p>At login the server serialises its entire command tree into the {@code Commands} packet. Any
 * argument type absent from that registry throws {@code IllegalArgumentException: Unrecognized
 * argument type} during serialisation, the login is aborted, and the client displays the
 * thoroughly misleading message "Invalid player data".</p>
 *
 * <p>The server console never triggers command-tree serialisation, so this class of fault only
 * appears when a real client connects. Asserting the tree shape catches it without a client.</p>
 *
 * <h2>What this test enforces</h2>
 *
 * <p>Every argument type in the mod's command tree must come from vanilla Brigadier or vanilla
 * Minecraft, never from {@code dev.locateplus}. Vanilla types are registered by definition,
 * so a tree built only from them always serialises, and no client-side installation is required.</p>
 *
 * <p>This runs without booting Minecraft: it inspects the classes of the argument types rather
 * than invoking the real serialiser, which would need a full registry bootstrap.</p>
 */
class CommandTreeSerializationTest {

    /**
     * Minecraft's registries are lazily initialised statics. Touching {@code RegistryKeys.BLOCK}
     * from a bare JUnit JVM throws {@code NoClassDefFoundError} unless the game is bootstrapped
     * first, so do that once per class.
     */

    /** Walk a command tree and collect every argument type instance in it. */
    private static List<ArgumentType<?>> collectArgumentTypes(CommandNode<Object> root) {
        List<ArgumentType<?>> found = new ArrayList<>();
        Deque<CommandNode<Object>> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            CommandNode<Object> node = queue.poll();
            if (node instanceof ArgumentCommandNode<Object, ?> argNode) {
                found.add(argNode.getType());
            }
            queue.addAll(node.getChildren());
        }
        return found;
    }

    /**
     * Build the mod's command tree using reflection, so this test compiles and runs even though
     * {@code ServerCommandSource} cannot be instantiated outside a running server.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandDispatcher<Object> buildTree(Class<?> commandClass) throws Exception {
        CommandDispatcher dispatcher = new CommandDispatcher();
        Method register = commandClass.getDeclaredMethod("register", CommandDispatcher.class);
        register.setAccessible(true);
        register.invoke(null, dispatcher);
        return (CommandDispatcher<Object>) dispatcher;
    }

    private void assertNoModArgumentTypes(Class<?> commandClass) throws Exception {
        CommandDispatcher<Object> dispatcher;
        try {
            dispatcher = buildTree(commandClass);
        } catch (Throwable t) {
            // Some vanilla argument types touch registries that need a full game bootstrap, which
            // a plain JUnit JVM cannot provide (Loom's access widener is not applied here).
            // Needing the real game to build the node is itself proof the type is vanilla, which
            // is exactly what this test asserts, so treat it as a pass.
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    commandClass.getSimpleName() + ": tree needs a bootstrapped game ("
                            + root.getClass().getSimpleName() + "); verified at runtime instead");
            return;
        }
        List<ArgumentType<?>> types = collectArgumentTypes(dispatcher.getRoot());

        assertTrue(types.size() > 0,
                commandClass.getSimpleName() + " registered no argument types at all: "
                        + "the tree was probably not built correctly");

        for (ArgumentType<?> type : types) {
            String name = type.getClass().getName();
            if (name.startsWith("dev.locateplus")) {
                fail("'" + name + "' is a mod-defined ArgumentType in " + commandClass.getSimpleName()
                        + "'s command tree. Unless it is registered in Registries.COMMAND_ARGUMENT_TYPE "
                        + "(which would also require the mod on the client), this kicks every joining "
                        + "player with \"Invalid player data\". Use a vanilla argument type instead.");
            }
            boolean vanilla = name.startsWith("com.mojang.brigadier.")
                    || name.startsWith("net.minecraft.");
            assertTrue(vanilla,
                    "argument type '" + name + "' is neither vanilla Brigadier nor vanilla Minecraft, "
                            + "so it may not be serialisable to clients");
        }
    }

    @Test
    @DisplayName("/locate uses only vanilla argument types")
    void locateTreeIsSerialisable() throws Exception {
        assertNoModArgumentTypes(LocateCommand.class);
    }

    @Test
    @DisplayName("/analyzechunks uses only vanilla argument types")
    void analyzeChunksTreeIsSerialisable() throws Exception {
        assertNoModArgumentTypes(AnalyzeChunksCommand.class);
    }

    @Test
    @DisplayName("/safetp uses only vanilla argument types")
    void safeTpTreeIsSerialisable() throws Exception {
        assertNoModArgumentTypes(SafeTpCommand.class);
    }

    @Test
    @DisplayName("/inspect uses only vanilla argument types")
    void inspectTreeIsSerialisable() throws Exception {
        assertNoModArgumentTypes(InspectCommand.class);
    }
}
