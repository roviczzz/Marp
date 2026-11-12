package me.roviczzz.marp.tpa;

import java.util.Map;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Builds interactive Adventure chat prompts for teleport requests without
 * enabling interactive tags inside {@link MessageService}'s MiniMessage parser.
 */
public final class TpaPresenter {

    private final Supplier<MessageService> messages;

    public TpaPresenter(Supplier<MessageService> messages) {
        this.messages = messages;
    }

    public Component requestReceived(TpaRequest req, int remainingSeconds, String anchorWorld) {
        MessageService msg = messages.get();
        String key = req.direction() == TpaDirection.TPA
                ? Messages.TPA_REQUEST_RECEIVED_TPA
                : Messages.TPA_REQUEST_RECEIVED_TPAHERE;
        Component body = msg.component(key, Map.of("requester", req.requesterName()));
        Component acceptBtn = acceptButton(req, remainingSeconds, anchorWorld);
        Component denyBtn = denyButton(req);
        return Component.empty()
                .append(body)
                .append(acceptBtn)
                .append(Component.space())
                .append(denyBtn);
    }

    public Component requestSent(TpaRequest req, int remainingSeconds) {
        MessageService msg = messages.get();
        Component body = msg.component(
                Messages.TPA_REQUEST_SENT,
                Map.of(
                        "target", req.targetName(),
                        "seconds", Integer.toString(remainingSeconds)));
        return Component.empty()
                .append(body)
                .append(cancelButton(req, remainingSeconds));
    }

    public Component alreadyPending(TpaRequest existing, int remainingSeconds) {
        MessageService msg = messages.get();
        Component body = msg.component(
                Messages.TPA_ALREADY_PENDING,
                Map.of("target", existing.targetName()));
        return Component.empty()
                .append(body)
                .append(cancelButton(existing, remainingSeconds));
    }

    public Component ambiguousEntry(TpaRequest req, int remainingSeconds, String anchorWorld) {
        MessageService msg = messages.get();
        String directionLabel = directionLabel(req);
        Component line = msg.component(
                Messages.TPA_AMBIGUOUS_ENTRY,
                Map.of(
                        "requester", req.requesterName(),
                        "direction", directionLabel,
                        "seconds", Integer.toString(remainingSeconds)));
        return Component.empty()
                .append(line)
                .append(acceptButton(req, remainingSeconds, anchorWorld))
                .append(Component.space())
                .append(denyButton(req));
    }

    public Component acceptButton(TpaRequest req, int remainingSeconds, String anchorWorld) {
        MessageService msg = messages.get();
        Component label = msg.component(Messages.TPA_BUTTON_ACCEPT);
        Component hover = msg.component(
                Messages.TPA_BUTTON_ACCEPT_HOVER,
                Map.of(
                        "requester", req.requesterName(),
                        "direction", directionLabel(req),
                        "world", anchorWorld == null ? "?" : anchorWorld,
                        "seconds", Integer.toString(Math.max(0, remainingSeconds))));
        return label
                .clickEvent(ClickEvent.runCommand("/tpaccept " + req.requesterName()))
                .hoverEvent(HoverEvent.showText(hover));
    }

    public Component denyButton(TpaRequest req) {
        MessageService msg = messages.get();
        Component label = msg.component(Messages.TPA_BUTTON_DENY);
        Component hover = msg.component(
                Messages.TPA_BUTTON_DENY_HOVER,
                Map.of("requester", req.requesterName()));
        return label
                .clickEvent(ClickEvent.runCommand("/tpdeny " + req.requesterName()))
                .hoverEvent(HoverEvent.showText(hover));
    }

    public Component cancelButton(TpaRequest req, int remainingSeconds) {
        MessageService msg = messages.get();
        Component label = msg.component(Messages.TPA_BUTTON_CANCEL);
        Component hover = msg.component(
                Messages.TPA_BUTTON_CANCEL_HOVER,
                Map.of(
                        "target", req.targetName(),
                        "seconds", Integer.toString(Math.max(0, remainingSeconds))));
        return label
                .clickEvent(ClickEvent.runCommand("/tpcancel"))
                .hoverEvent(HoverEvent.showText(hover));
    }

    private static String directionLabel(TpaRequest req) {
        return req.direction() == TpaDirection.TPA
                ? req.requesterName() + " -> you"
                : "you -> " + req.requesterName();
    }
}
