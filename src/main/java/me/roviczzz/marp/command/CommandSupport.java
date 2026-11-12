package me.roviczzz.marp.command;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MarpConfig;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shared command precondition checks so every denial goes through
 * {@code messages.yml} consistently.
 */
public final class CommandSupport {

    private final BooleanSupplier storageReady;
    private final Supplier<MarpConfig> config;
    private final Supplier<MessageService> messages;
    private final Function<UUID, Player> playerLookup;

    public CommandSupport(
            BooleanSupplier storageReady,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages) {
        this(storageReady, config, messages, Bukkit::getPlayer);
    }

    public CommandSupport(
            BooleanSupplier storageReady,
            Supplier<MarpConfig> config,
            Supplier<MessageService> messages,
            Function<UUID, Player> playerLookup) {
        this.storageReady = storageReady;
        this.config = config;
        this.messages = messages;
        this.playerLookup = playerLookup;
    }

    public Player onlinePlayer(UUID uuid) {
        Player player = playerLookup.apply(uuid);
        return (player != null && player.isOnline()) ? player : null;
    }

    public MarpConfig config() {
        return config.get();
    }

    public MessageService messages() {
        return messages.get();
    }

    public boolean isStorageReady() {
        return storageReady.getAsBoolean();
    }

    public Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages().send(sender, Messages.PLAYER_ONLY);
        return null;
    }

    public boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages().send(sender, Messages.NO_PERMISSION);
        return false;
    }

    public boolean requireStorageReady(CommandSender sender) {
        if (storageReady.getAsBoolean()) {
            return true;
        }
        messages().send(sender, Messages.STORAGE_NOT_READY);
        return false;
    }

    public boolean requireFeatureEnabled(CommandSender sender, boolean enabled) {
        if (enabled) {
            return true;
        }
        messages().send(sender, Messages.FEATURE_DISABLED);
        return false;
    }
}
