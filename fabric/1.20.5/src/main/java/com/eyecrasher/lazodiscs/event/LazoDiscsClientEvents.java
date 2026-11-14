package com.eyecrasher.lazodiscs.event;

import com.eyecrasher.lazodiscs.data.DiscDataUtil;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

public final class LazoDiscsClientEvents implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ItemTooltipCallback.EVENT.register(
                (stack, tooltipContext, tooltipType, lines) -> {
                    if (!DiscDataUtil.hasCustomDisc(stack)) {
                        return;
                    }

                    if (lines.size() > 1) {
                        lines.subList(1, lines.size()).clear();
                    }
                });
    }
}
