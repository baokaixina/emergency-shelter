package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.PacketGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 服务端发给玩家的数据包超过联机上限：设法缩小，实在不行就丢弃这一个，而不是让玩家断开连接。 */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
    @Shadow
    @Final
    private ProtocolInfo<?> protocolInfo;

    @WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V")
    private void emergencyshelter$guardSize(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, Operation<Void> original) throws Exception {
        int start = out.writerIndex();
        original.call(ctx, packet, out);
        if (protocolInfo.flow() == PacketFlow.CLIENTBOUND && protocolInfo.id() == ConnectionProtocol.PLAY) {
            PacketGuard.checkEncoded(ctx, packet, out, start, () -> original.call(ctx, packet, out));
        }
    }
}
