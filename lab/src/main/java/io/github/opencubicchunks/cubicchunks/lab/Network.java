package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;

public final class Network {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("cubiclab");
    public static void initialize() {
        CHANNEL.registerMessage(ClientHandler.class, ClientMessage.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(ServerHandler.class, ServerMessage.class, 1, Side.SERVER);
    }
    public static class Message implements IMessage {
        public String json;
        public Message() { }
        public Message(JsonObject data) { json = data.toString(); }
        public void fromBytes(ByteBuf buf) {
            json = LabControlCodec.decode(ByteBufUtils.readUTF8String(buf));
        }
        public void toBytes(ByteBuf buf) { ByteBufUtils.writeUTF8String(buf, LabControlCodec.encode(json)); }
    }
    public static final class ClientMessage extends Message {
        public ClientMessage() { }
        public ClientMessage(JsonObject data) { super(data); }
    }
    public static final class ServerMessage extends Message {
        public ServerMessage() { }
        public ServerMessage(JsonObject data) { super(data); }
    }
    public static final class ClientHandler implements IMessageHandler<ClientMessage, IMessage> {
        public IMessage onMessage(ClientMessage message, MessageContext context) { CubicLab.proxy.clientMessage(message.json); return null; }
    }
    public static final class ServerHandler implements IMessageHandler<ServerMessage, IMessage> {
        public IMessage onMessage(ServerMessage message, MessageContext context) {
            context.getServerHandler().player.getServerWorld().addScheduledTask(() ->
                CubicLab.receive(context.getServerHandler().player, message.json));
            return null;
        }
    }
}
