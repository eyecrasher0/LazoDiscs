package com.eyecrasher.lazodiscs.command;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestRetirementTest {
    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "equals" -> instance == args[0];
                    case "hashCode" -> System.identityHashCode(instance);
                    case "toString" -> "test player " + id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static Object install(LazoDiscsCommands commands, Player owner, CompletableFuture<?> future) throws Exception {
        Class<?> type = Class.forName(LazoDiscsCommands.class.getName() + "$Pending");
        var constructor = type.getDeclaredConstructor(Player.class, ItemStack.class, int.class);
        constructor.setAccessible(true);
        Object work = constructor.newInstance(owner, null, -1);
        var task = type.getDeclaredField("future");task.setAccessible(true);task.set(work, future);
        var field = LazoDiscsCommands.class.getDeclaredField("pending");field.setAccessible(true);
        ((Map<UUID, Object>) field.get(commands)).put(owner.getUniqueId(), work);
        return work;
    }

    @Test void anOldLoginCallbackCannotCancelTheReconnectedPlayersRequest() throws Exception {
        var commands = new LazoDiscsCommands(null);
        UUID id = UUID.randomUUID();Player oldLogin = player(id), newLogin = player(id);
        var previous = new CompletableFuture<Void>();install(commands, oldLogin, previous);
        Runnable retired = commands.retirement(oldLogin);
        var current = new CompletableFuture<Void>();install(commands, newLogin, current);
        retired.run();
        commands.retirement(oldLogin).run();
        assertFalse(current.isCancelled());
        commands.retirement(newLogin).run();
        assertTrue(current.isCancelled());
        assertTrue(previous.isCancelled());
    }

    @Test void aLateCallbackCannotCancelANewerRequestInTheSameLogin() throws Exception {
        var commands = new LazoDiscsCommands(null);Player owner = player(UUID.randomUUID());
        install(commands, owner, new CompletableFuture<Void>());
        Runnable retired = commands.retirement(owner);
        var newer = new CompletableFuture<Void>();install(commands, owner, newer);
        retired.run();
        assertFalse(newer.isCancelled());
        commands.retirement(owner).run();
        assertTrue(newer.isCancelled());
    }

    @Test void retiringTheCurrentRequestCancelsOnlyThatRequest() throws Exception {
        var commands = new LazoDiscsCommands(null);Player owner = player(UUID.randomUUID());
        var current = new CompletableFuture<Void>();install(commands, owner, current);
        Runnable retired = commands.retirement(owner);
        retired.run();retired.run();
        assertTrue(current.isCancelled());
        var next = new CompletableFuture<Void>();install(commands, owner, next);
        retired.run();
        assertFalse(next.isCancelled());
    }
}
