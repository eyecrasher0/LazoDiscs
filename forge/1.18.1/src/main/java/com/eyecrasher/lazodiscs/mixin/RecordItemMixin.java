package com.eyecrasher.lazodiscs.mixin;

import com.eyecrasher.lazodiscs.LazoDiscsServerBootstrap;
import com.eyecrasher.lazodiscs.access.LazoDiscJukeboxAccess;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.server.LazoDiscsPermissions;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;

import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(RecordItem.class)
public abstract class RecordItemMixin {

    /**
     * Custom LazoDiscs are still based on vanilla music disc items, but vanilla would normally play
     * the original disc sound and show the original song name. We cancel vanilla insertion for
     * LazoDiscs, insert the item silently, show the LazoDisc title, and let Plasmo Voice play only
     * the custom URL audio.
     */
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void lazodiscs$insertDisc(
            UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();
        Player player = context.getPlayer();
        if (player == null) return;
        Optional<CustomDiscData> data = DiscDataUtil.read(stack);

        if (data.isEmpty()) {
            return;
        }

        BlockState blockstate = level.getBlockState(pos);

        if (!blockstate.is(Blocks.JUKEBOX) || blockstate.getValue(JukeboxBlock.HAS_RECORD)) {
            return;
        }

        if (!level.isClientSide()) {

            if (!LazoDiscsServerBootstrap.isLoaded()) {
                player.displayClientMessage(LazoDiscsText.plasmoVoiceRequired(), true);

                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }

            if (!LazoDiscsPermissions.canPlay(player)) {
                player.displayClientMessage(LazoDiscsText.playNoPermission(), true);

                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }

            if (!(level.getBlockEntity(pos) instanceof JukeboxBlockEntity)) {
                cir.setReturnValue(InteractionResult.FAIL);
                return;
            }

            ItemStack record = stack.copy();
            record.setCount(1);
            if (!player.getAbilities().instabuild) stack.shrink(1);

            if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox) {

                if (jukebox instanceof LazoDiscJukeboxAccess access) {
                    access.lazodiscs$setLazoDiscItem(record);
                } else {
                    jukebox.setRecord(record);
                }

                // setRecord's mixin starts playback; do not restart the same disc here.
                level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            }

            player.awardStat(Stats.PLAY_RECORD);

            String title = data.get().title();

            player.displayClientMessage(
                    new net.minecraft.network.chat.TextComponent(LazoDiscsText.nowPlaying(title)),
                    true);
        }

        cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide()));
    }
}
