package com.emergencyshelter.box;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.salvage.ItemSalvage;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class ShelterBox {
    private ShelterBox() {
    }

    /** 登录时：把背包和末影箱里的占位物品移进避险箱，并告诉玩家。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("ShelterBox.onLogin", () -> onLoginUnsafe(event));
    }

    private static void onLoginUnsafe(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ShelterBoxData data = ShelterBoxData.get(player.server);
        int moved = collect(player.getInventory(), data, player) + collect(player.getEnderChestInventory(), data, player);
        if (moved > 0) {
            player.inventoryMenu.broadcastChanges();
            player.sendSystemMessage(Component.translatable("emergencyshelter.box.moved", moved)
                    .withStyle(ChatFormatting.GOLD).append(" ").append(openLink()));
        }
        List<ItemStack> items = data.items(player.getUUID());
        long restored = items.stream().filter(s -> !s.isEmpty() && !ItemSalvage.isPlaceholder(s)).count();
        long waiting = items.size() - restored;
        if (restored > 0) {
            player.sendSystemMessage(Component.translatable("emergencyshelter.box.restored", restored)
                    .withStyle(ChatFormatting.GREEN).append(" ").append(openLink()));
        } else if (moved == 0 && waiting > 0) {
            player.sendSystemMessage(Component.translatable("emergencyshelter.box.waiting", waiting)
                    .withStyle(ChatFormatting.GRAY).append(" ").append(openLink()));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("ShelterBox.onLogout", () -> onLogoutUnsafe(event));
    }

    private static void onLogoutUnsafe(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.containerMenu instanceof ChestMenu menu
                && menu.getContainer() instanceof BoxPageContainer) {
            player.closeContainer();
        }
        // 玩家数据在下线时立即写盘，避险箱却要等下次自动保存：中间崩溃的话，登录时移进箱子的东西两边都没有了
        if (event.getEntity() instanceof ServerPlayer player) {
            ShelterBoxData.get(player.server).saveNow(player.server);
        }
    }

    private static int collect(Container container, ShelterBoxData data, ServerPlayer player) {
        int moved = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (ItemSalvage.isPlaceholder(stack)) {
                data.add(player.getUUID(), stack.copy());
                container.setItem(i, ItemStack.EMPTY);
                moved += stack.getCount();
            }
        }
        if (moved > 0) {
            container.setChanged();
        }
        return moved;
    }

    public static void open(ServerPlayer player, int page) {
        // 先关闭当前界面，让之前打开的那一页先写回，避免页码错位
        player.closeContainer();
        ShelterBoxData data = ShelterBoxData.get(player.server);
        int size = data.items(player.getUUID()).size();
        int pages = Math.max(1, (size + BoxPageContainer.SIZE - 1) / BoxPageContainer.SIZE);
        int current = Math.max(1, Math.min(page, pages));
        BoxPageContainer container = new BoxPageContainer(data, player.getUUID(), (current - 1) * BoxPageContainer.SIZE);
        Component title = Component.translatable("container.emergencyshelter.box", current, pages);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.sixRows(id, inventory, container), title));
        if (pages > 1) {
            MutableComponent nav = Component.translatable("emergencyshelter.box.pages", current, pages).withStyle(ChatFormatting.GRAY);
            for (int i = 1; i <= pages; i++) {
                nav.append(" ").append(Component.literal("[" + i + "]").withStyle(Style.EMPTY
                        .withColor(i == current ? ChatFormatting.YELLOW : ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/emergencyshelter box " + i))));
            }
            player.sendSystemMessage(nav);
        }
    }

    public static Component openLink() {
        return Component.translatable("emergencyshelter.box.open").withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/emergencyshelter box"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("emergencyshelter.box.open.hover"))));
    }
}
