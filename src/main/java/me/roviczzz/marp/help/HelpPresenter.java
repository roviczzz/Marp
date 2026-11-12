package me.roviczzz.marp.help;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import me.roviczzz.marp.config.MessageService;
import me.roviczzz.marp.config.Messages;
import me.roviczzz.marp.model.Page;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

/**
 * Builds interactive, paginated, category-grouped Adventure chat components for
 * the {@code /marp help} directory.
 */
public final class HelpPresenter {

    private final Supplier<MessageService> messages;

    public HelpPresenter(Supplier<MessageService> messages) {
        this.messages = messages;
    }

    public Component renderPage(
            List<HelpEntry> allVisible,
            List<HelpEntry> pageSlice,
            Page page,
            boolean canViewAdminPerms) {
        MessageService msg = messages.get();
        if (allVisible == null || allVisible.isEmpty() || pageSlice.isEmpty()) {
            return msg.component(Messages.HELP_NONE_AVAILABLE);
        }

        Component out = msg.component(
                Messages.HELP_HEADER,
                Map.of(
                        "page", Integer.toString(page.number()),
                        "pages", Integer.toString(page.pages())));

        HelpCategory currentCategory = null;
        for (int i = 0; i < pageSlice.size(); i++) {
            HelpEntry entry = pageSlice.get(i);
            if (entry.category() != currentCategory) {
                currentCategory = entry.category();
                boolean isContinuation = (i == 0 && page.number() > 1 && allVisible.indexOf(entry) > 0
                        && allVisible.get(allVisible.indexOf(entry) - 1).category() == currentCategory);
                String catName = msg.plain(msg.component(currentCategory.messageKey()));
                Component catHeader = msg.component(
                        isContinuation ? Messages.HELP_CATEGORY_CONT : Messages.HELP_CATEGORY_HEADER,
                        Map.of("category", catName));
                out = out.append(Component.newline()).append(catHeader);
            }

            Component line = renderEntryLine(entry, canViewAdminPerms, msg);
            out = out.append(Component.newline()).append(line);
        }

        Component footer = renderFooter(page, msg);
        return out.append(Component.newline()).append(footer);
    }

    private Component renderEntryLine(HelpEntry entry, boolean canViewAdminPerms, MessageService msg) {
        String desc = msg.plain(msg.component(entry.descriptionKey()));
        Component line = msg.component(
                Messages.HELP_ENTRY,
                Map.of("syntax", entry.syntax(), "description", desc));

        String hint = msg.plain(msg.component(
                entry.clickMode() == HelpClickMode.RUN_COMMAND
                        ? Messages.HELP_HOVER_CLICK_RUN
                        : Messages.HELP_HOVER_CLICK_SUGGEST));
        Component hover = msg.component(
                Messages.HELP_HOVER,
                Map.of(
                        "syntax", entry.syntax(),
                        "description", desc,
                        "example", entry.example(),
                        "hint", hint));
        if (canViewAdminPerms) {
            Component adminPerm = msg.component(
                    Messages.HELP_HOVER_ADMIN_PERM, Map.of("permission", entry.permission()));
            hover = hover.append(adminPerm);
        }

        ClickEvent click = entry.clickMode() == HelpClickMode.RUN_COMMAND
                ? ClickEvent.runCommand(entry.clickPayload())
                : ClickEvent.suggestCommand(entry.clickPayload());

        return line.clickEvent(click).hoverEvent(HoverEvent.showText(hover));
    }

    private Component renderFooter(Page page, MessageService msg) {
        Component prevBtn;
        if (page.hasPrevious()) {
            int prevPage = page.number() - 1;
            prevBtn = msg.component(Messages.HELP_PREV)
                    .clickEvent(ClickEvent.runCommand("/marp help " + prevPage))
                    .hoverEvent(HoverEvent.showText(
                            msg.component(Messages.HELP_PREV_HOVER, Map.of("page", Integer.toString(prevPage)))));
        } else {
            prevBtn = msg.component(Messages.HELP_PREV_DISABLED);
        }

        Component nextBtn;
        if (page.hasNext()) {
            int nextPage = page.number() + 1;
            nextBtn = msg.component(Messages.HELP_NEXT)
                    .clickEvent(ClickEvent.runCommand("/marp help " + nextPage))
                    .hoverEvent(HoverEvent.showText(
                            msg.component(Messages.HELP_NEXT_HOVER, Map.of("page", Integer.toString(nextPage)))));
        } else {
            nextBtn = msg.component(Messages.HELP_NEXT_DISABLED);
        }

        String template = msg.raw(Messages.HELP_FOOTER);
        if (template == null) {
            return Component.empty();
        }
        String replaced = template
                .replace("{page}", Integer.toString(page.number()))
                .replace("{pages}", Integer.toString(page.pages()));

        int prevIdx = replaced.indexOf("{prev}");
        int nextIdx = replaced.indexOf("{next}");
        if (prevIdx < 0 || nextIdx < 0) {
            return Component.empty()
                    .append(prevBtn)
                    .append(Component.space())
                    .append(msg.render(replaced, Map.of(), true))
                    .append(Component.space())
                    .append(nextBtn);
        }

        Component left = msg.render(replaced.substring(0, prevIdx), Map.of(), true);
        Component mid = msg.render(replaced.substring(prevIdx + "{prev}".length(), nextIdx), Map.of(), true);
        Component right = msg.render(replaced.substring(nextIdx + "{next}".length()), Map.of(), true);

        return Component.empty()
                .append(left)
                .append(prevBtn)
                .append(mid)
                .append(nextBtn)
                .append(right);
    }
}
