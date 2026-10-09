package com.emergencyshelter.box;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 避险箱的一页（54 格）。每次格子变化都立即写回列表：
 * 如果等到关闭时才写回，玩家取出物品后、关闭之前遇到自动保存再崩溃，这件物品会同时留在避险箱和背包里。
 */
public final class BoxPageContainer extends SimpleContainer {
    public static final int SIZE = 54;

    private final ShelterBoxData data;
    private final UUID owner;
    private final int start;
    /** 这一页目前在列表里占的长度。 */
    private int taken;
    private boolean ready;
    private boolean written;

    public BoxPageContainer(ShelterBoxData data, UUID owner, int start) {
        super(SIZE);
        this.data = data;
        this.owner = owner;
        this.start = start;
        List<ItemStack> items = data.items(owner);
        int count = 0;
        for (int i = 0; i < SIZE && start + i < items.size(); i++) {
            setItem(i, items.get(start + i));
            count++;
        }
        this.taken = count;
        this.ready = true;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (ready && !written) {
            sync();
        }
    }

    private void sync() {
        List<ItemStack> contents = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            contents.add(getItem(i));
        }
        taken = data.writeBack(owner, start, taken, contents);
    }

    @Override
    public void stopOpen(Player player) {
        super.stopOpen(player);
        if (written) {
            return;
        }
        sync();
        written = true;
    }

    @Override
    public boolean stillValid(Player player) {
        return !written && player.getUUID().equals(owner) && player.isAlive();
    }
}
