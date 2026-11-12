package me.roviczzz.marp.tpa;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import me.roviczzz.marp.command.CommandSupport;
import me.roviczzz.marp.command.CompletionSupport;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.LocationSnapshot;
import me.roviczzz.marp.settings.PlayerSettingsService;
import me.roviczzz.marp.teleport.TeleportReason;
import me.roviczzz.marp.teleport.TeleportService;
import me.roviczzz.marp.teleport.TeleportService.Outcome;
import me.roviczzz.marp.tpa.TpaService.AcceptOutcome;
import me.roviczzz.marp.tpa.TpaService.CancelOutcome;
import me.roviczzz.marp.tpa.TpaService.DenyOutcome;
import me.roviczzz.marp.tpa.TpaService.SendOutcome;
import me.roviczzz.marp.util.LocationCodec;
import me.roviczzz.marp.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Handles {@code /tpa}, {@code /tpahere}, {@code /tpaccept}, {@code /tpdeny},
 * {@code /tpcancel}, and {@code /tptoggle}.
 */
public final class TpaCommand implements CommandExecutor, TabCompleter {

    private final CommandSupport support;
    private final TpaService tpaService;
    private final TpaPresenter presenter;
    private final TeleportService teleportService;
    private final PlayerSettingsService settings;
    private final Scheduler scheduler;
    private final LongSupplier clock;

    public TpaCommand(
            CommandSupport support,
            TpaService tpaService,
            TpaPresenter presenter,
            TeleportService teleportService,
            PlayerSettingsService settings,
            Scheduler scheduler,
            LongSupplier clock) {
        this.support = support;
        this.tpaService = tpaService;
        this.presenter = presenter;
        this.teleportService = teleportService;
        this.settings = settings;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = support.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (!support.requirePermission(player, "marp." + cmd)) {
            return true;
        }
        if (!support.requireFeatureEnabled(player, support.config().tpaEnabled())) {
            return true;
        }

        switch (cmd) {
            case "tpa" -> handleSend(player, args, TpaDirection.TPA, "/tpa <player>");
            case "tpahere" -> handleSend(player, args, TpaDirection.TPA_HERE, "/tpahere <player>");
            case "tpaccept" -> handleAccept(player, args);
            case "tpdeny" -> handleDeny(player, args);
            case "tpcancel" -> handleCancel(player, args);
            case "tptoggle" -> handleToggle(player, args);
            default -> {
            }
        }
        return true;
    }

    private void handleSend(Player requester, String[] args, TpaDirection direction, String usage) {
        if (args.length != 1) {
            support.messages().send(requester, Messages.INVALID_USAGE, Map.of("usage", usage));
            return;
        }
        Player target = findVisibleOnlinePlayer(requester, args[0]);
        if (target == null) {
            support.messages().send(requester, Messages.UNKNOWN_PLAYER, Map.of("player", args[0]));
            return;
        }
        if (direction == TpaDirection.TPA) {
            long cd = teleportService.cooldowns().remainingSeconds(
                    requester.getUniqueId(),
                    TeleportReason.TPA,
                    requester.hasPermission("marp.bypass.cooldown"));
            if (cd > 0L) {
                support.messages().send(
                        requester, Messages.TELEPORT_COOLDOWN, Map.of("seconds", Long.toString(cd)));
                return;
            }
        }

        String reqWorld = requester.getWorld() != null ? requester.getWorld().getName() : "";
        String tgtWorld = target.getWorld() != null ? target.getWorld().getName() : "";
        SendOutcome outcome = tpaService.sendRequest(
                direction,
                requester.getUniqueId(),
                requester.getName(),
                reqWorld,
                target.getUniqueId(),
                target.getName(),
                tgtWorld);

        long now = clock.getAsLong();
        if (outcome instanceof SendOutcome.Sent sent) {
            TpaRequest req = sent.request();
            int rem = req.remainingSeconds(now);
            String anchorWorld = direction == TpaDirection.TPA ? tgtWorld : reqWorld;
            requester.sendMessage(presenter.requestSent(req, rem));
            target.sendMessage(presenter.requestReceived(req, rem, anchorWorld));
        } else if (outcome instanceof SendOutcome.SelfRequest) {
            support.messages().send(requester, Messages.TPA_CANNOT_REQUEST_SELF);
        } else if (outcome instanceof SendOutcome.AlreadyPending already) {
            requester.sendMessage(presenter.alreadyPending(
                    already.existing(), already.existing().remainingSeconds(now)));
        } else if (outcome instanceof SendOutcome.TargetToggledOff) {
            support.messages().send(
                    requester, Messages.TPA_TARGET_TOGGLED_OFF, Map.of("target", target.getName()));
        } else if (outcome instanceof SendOutcome.TargetBusy) {
            support.messages().send(
                    requester, Messages.TPA_TARGET_BUSY, Map.of("target", target.getName()));
        } else if (outcome instanceof SendOutcome.DenyCooldown cd) {
            support.messages().send(
                    requester,
                    Messages.TPA_REQUEST_COOLDOWN,
                    Map.of("seconds", Long.toString(cd.remainingSeconds()), "target", target.getName()));
        } else if (outcome instanceof SendOutcome.CrossWorldDisabled) {
            support.messages().send(requester, Messages.CROSS_WORLD_DISABLED);
        } else if (outcome instanceof SendOutcome.MoverInCombat combat) {
            support.messages().send(
                    requester,
                    Messages.TPA_IN_COMBAT,
                    Map.of("seconds", Long.toString(combat.remainingSeconds())));
        } else if (outcome instanceof SendOutcome.TargetInCombat) {
            support.messages().send(
                    requester, Messages.TPA_TARGET_IN_COMBAT, Map.of("target", target.getName()));
        }
    }

    private void handleAccept(Player accepter, String[] args) {
        if (args.length > 1) {
            support.messages().send(accepter, Messages.INVALID_USAGE, Map.of("usage", "/tpaccept [player]"));
            return;
        }
        String query = args.length == 1 ? args[0] : null;
        String accepterWorld = accepter.getWorld() != null ? accepter.getWorld().getName() : "";
        AcceptOutcome outcome = tpaService.prepareAccept(
                accepter.getUniqueId(),
                query,
                accepterWorld,
                uuid -> {
                    Player p = support.onlinePlayer(uuid);
                    return p != null && p.getWorld() != null ? p.getWorld().getName() : null;
                });

        long now = clock.getAsLong();
        if (outcome instanceof AcceptOutcome.NonePending) {
            support.messages().send(accepter, Messages.TPA_NONE_PENDING);
            return;
        }
        if (outcome instanceof AcceptOutcome.NoPendingFrom notFound) {
            support.messages().send(
                    accepter, Messages.TPA_NO_PENDING_FROM, Map.of("player", notFound.query()));
            return;
        }
        if (outcome instanceof AcceptOutcome.Ambiguous ambiguous) {
            sendAmbiguousList(accepter, ambiguous.pending(), now);
            return;
        }
        if (outcome instanceof AcceptOutcome.RequesterOffline offline) {
            support.messages().send(
                    accepter, Messages.UNKNOWN_PLAYER, Map.of("player", offline.request().requesterName()));
            return;
        }
        if (outcome instanceof AcceptOutcome.CrossWorldDisabled) {
            support.messages().send(accepter, Messages.CROSS_WORLD_DISABLED);
            return;
        }
        if (outcome instanceof AcceptOutcome.MoverInCombat combat) {
            support.messages().send(
                    accepter, Messages.TPA_IN_COMBAT, Map.of("seconds", Long.toString(combat.remainingSeconds())));
            return;
        }
        if (outcome instanceof AcceptOutcome.TargetInCombat) {
            support.messages().send(
                    accepter, Messages.TPA_TARGET_IN_COMBAT, Map.of("target", accepter.getName()));
            return;
        }

        TpaRequest req = ((AcceptOutcome.Ready) outcome).request();
        Player requester = support.onlinePlayer(req.requesterUuid());
        if (requester == null || requester.isDead()) {
            tpaService.consumeAccepted(req.id());
            support.messages().send(accepter, Messages.UNKNOWN_PLAYER, Map.of("player", req.requesterName()));
            return;
        }

        Player mover = req.direction() == TpaDirection.TPA ? requester : accepter;
        Player anchor = req.direction() == TpaDirection.TPA ? accepter : requester;
        long moverCd = teleportService.cooldowns().remainingSeconds(
                mover.getUniqueId(),
                TeleportReason.TPA,
                mover.hasPermission("marp.bypass.cooldown"));
        if (moverCd > 0L) {
            if (!mover.getUniqueId().equals(accepter.getUniqueId())) {
                support.messages().send(
                        mover, Messages.TELEPORT_COOLDOWN, Map.of("seconds", Long.toString(moverCd)));
            }
            support.messages().send(
                    accepter, Messages.TELEPORT_COOLDOWN, Map.of("seconds", Long.toString(moverCd)));
            return;
        }

        LocationSnapshot anchorSnapshot = LocationCodec.fromLocation(anchor.getLocation());
        if (!tpaService.consumeAccepted(req.id())) {
            support.messages().send(accepter, Messages.TPA_NONE_PENDING);
            return;
        }

        support.messages().send(
                requester, Messages.TPA_ACCEPTED_SENDER, Map.of("target", accepter.getName()));
        support.messages().send(
                accepter, Messages.TPA_ACCEPTED_TARGET, Map.of("requester", requester.getName()));

        teleportService.requestTpaTeleport(
                mover.getUniqueId(), anchor.getUniqueId(), anchor.getName(), anchorSnapshot)
                .whenComplete((tpOutcome, error) -> {
                    if (tpOutcome instanceof Outcome.OnCooldown) {
                        // Cooldown message already sent to mover by TeleportService.
                    }
                });
    }

    private void handleDeny(Player denier, String[] args) {
        if (args.length > 1) {
            support.messages().send(denier, Messages.INVALID_USAGE, Map.of("usage", "/tpdeny [player]"));
            return;
        }
        String query = args.length == 1 ? args[0] : null;
        DenyOutcome outcome = tpaService.deny(denier.getUniqueId(), query);
        long now = clock.getAsLong();

        if (outcome instanceof DenyOutcome.NonePending) {
            support.messages().send(denier, Messages.TPA_NONE_PENDING);
        } else if (outcome instanceof DenyOutcome.NoPendingFrom notFound) {
            support.messages().send(
                    denier, Messages.TPA_NO_PENDING_FROM, Map.of("player", notFound.query()));
        } else if (outcome instanceof DenyOutcome.Ambiguous ambiguous) {
            sendAmbiguousList(denier, ambiguous.pending(), now);
        } else if (outcome instanceof DenyOutcome.Denied denied) {
            TpaRequest req = denied.request();
            support.messages().send(
                    denier, Messages.TPA_DENIED_TARGET, Map.of("requester", req.requesterName()));
            Player requester = support.onlinePlayer(req.requesterUuid());
            if (requester != null) {
                support.messages().send(
                        requester, Messages.TPA_DENIED_SENDER, Map.of("target", denier.getName()));
            }
        }
    }

    private void handleCancel(Player requester, String[] args) {
        if (args.length != 0) {
            support.messages().send(requester, Messages.INVALID_USAGE, Map.of("usage", "/tpcancel"));
            return;
        }
        CancelOutcome outcome = tpaService.cancel(requester.getUniqueId());
        if (outcome instanceof CancelOutcome.NonePending) {
            support.messages().send(requester, Messages.TPA_NONE_PENDING);
        } else if (outcome instanceof CancelOutcome.Cancelled cancelled) {
            TpaRequest req = cancelled.request();
            support.messages().send(
                    requester, Messages.TPA_CANCELLED_SENDER, Map.of("target", req.targetName()));
            Player target = support.onlinePlayer(req.targetUuid());
            if (target != null) {
                support.messages().send(
                        target, Messages.TPA_CANCELLED_TARGET, Map.of("requester", requester.getName()));
            }
        }
    }

    private void handleToggle(Player player, String[] args) {
        if (!support.requireStorageReady(player)) {
            return;
        }
        if (args.length > 1) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/tptoggle [on|off]"));
            return;
        }
        UUID uuid = player.getUniqueId();
        var future = switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
            case "" -> settings.toggleTpa(uuid);
            case "on", "block", "disable" -> settings.setTpaDisabled(uuid, true);
            case "off", "allow", "enable" -> settings.setTpaDisabled(uuid, false);
            default -> null;
        };
        if (future == null) {
            support.messages().send(player, Messages.INVALID_USAGE, Map.of("usage", "/tptoggle [on|off]"));
            return;
        }
        future.whenComplete((updated, error) -> scheduler.runOnMain(() -> {
            Player online = support.onlinePlayer(uuid);
            if (online == null) {
                return;
            }
            if (error != null || updated == null) {
                support.messages().send(online, Messages.STORAGE_ERROR);
                return;
            }
            support.messages().send(
                    online, updated.tpaDisabled() ? Messages.TPA_TOGGLE_ON : Messages.TPA_TOGGLE_OFF);
        }));
    }

    private void sendAmbiguousList(Player target, List<TpaRequest> pending, long now) {
        support.messages().send(
                target,
                Messages.TPA_AMBIGUOUS_HEADER,
                Map.of("count", Integer.toString(pending.size())));
        for (TpaRequest req : pending) {
            Player requester = support.onlinePlayer(req.requesterUuid());
            String anchorWorld = req.direction() == TpaDirection.TPA
                    ? (target.getWorld() != null ? target.getWorld().getName() : "?")
                    : (requester != null && requester.getWorld() != null ? requester.getWorld().getName() : "?");
            target.sendMessage(presenter.ambiguousEntry(req, req.remainingSeconds(now), anchorWorld));
        }
    }

    private static Player findVisibleOnlinePlayer(Player viewer, String rawName) {
        Player exact = Bukkit.getPlayerExact(rawName);
        if (exact != null && exact.isOnline() && viewer.canSee(exact)) {
            return exact;
        }
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.getName().equalsIgnoreCase(rawName) && viewer.canSee(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)
                || !support.config().tpaEnabled()
                || args.length != 1) {
            return List.of();
        }
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (!player.hasPermission("marp." + cmd)) {
            return List.of();
        }
        return switch (cmd) {
            case "tpa", "tpahere" -> {
                List<String> names = Bukkit.getOnlinePlayers().stream()
                        .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                        .filter(player::canSee)
                        .map(Player::getName)
                        .toList();
                yield CompletionSupport.completeNames(names, args[0], CompletionSupport.MAX_SUGGESTIONS);
            }
            case "tpaccept", "tpdeny" -> tpaService.suggestIncomingRequesters(
                    player.getUniqueId(), args[0], CompletionSupport.MAX_SUGGESTIONS);
            case "tptoggle" -> CompletionSupport.completeNames(List.of("on", "off"), args[0], 2);
            default -> List.of();
        };
    }
}
