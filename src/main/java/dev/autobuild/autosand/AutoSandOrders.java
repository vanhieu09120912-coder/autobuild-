package autosand;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/**
 * AutoSandOrders (Fabric 1.21.11, Mojang mappings)
 *
 * Luồng: đào cát (Baritone + Nuker) -> đầy túi -> dừng đào -> mở /orders
 *        -> bấm đơn ĐẦU TIÊN (ô 0) -> shift-click hết cát vào -> đóng GUI
 *        -> bấm nút XÁC NHẬN -> bật lại Baritone + Nuker.
 *
 * Trong game: /autosand               bật / tắt
 *             /autosand cmd <lệnh>    đổi lệnh mở orders (không có dấu /)
 */
public class AutoSandOrders implements ClientModInitializer {

    // ===================== CẤU HÌNH (sửa ở đây) =====================
    /** Lệnh mở danh sách đơn hàng (không có dấu "/"). Đổi trong game bằng /autosand cmd ... */
    static String ORDER_COMMAND = "orders sand";

    /** Chạy trước khi đi giao: dừng Baritone + tắt Nuker. Mặc định theo Meteor Client. */
    static final List<String> PAUSE_CMDS = List.of("#stop", ".toggle nuker off");

    /** Chạy sau khi giao xong (hoặc khi bật mod): bật Nuker + cho Baritone đào cát. */
    static final List<String> RESUME_CMDS = List.of(".toggle nuker on", "#mine sand");

    /** Số tick chờ giữa các thao tác (20 tick = 1 giây). Server hay kick thì tăng lên. */
    static final int ACTION_DELAY = 4;
    /** Quá số tick này mà 1 bước không xong thì huỷ và quay lại đào. */
    static final int STEP_TIMEOUT = 200;
    /** Nghỉ sau mỗi lần giao để khỏi bị kích hoạt lại ngay. */
    static final int COOLDOWN_AFTER = 60;
    /** Ô nút XÁC NHẬN (màn hình 3 hàng). Mod ưu tiên tìm theo tên "xác nhận". */
    static final int CONFIRM_SLOT = 16;
    // =================================================================

    enum S { MINING, WAIT_ORDERS, WAIT_DELIVER, DEPOSIT, WAIT_CONFIRM, FINISH }

    static S state = S.MINING;
    static boolean enabled = false;
    static int wait = 0, timeout = 0, cooldown = 0, lastSync = -1, clicks = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoSandOrders::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("autosand")
                .executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    enabled = !enabled;
                    state = S.MINING;
                    cooldown = 0;
                    wait = 0;
                    if (mc.player != null) {
                        if (enabled) run(mc.player, RESUME_CMDS);
                        else run(mc.player, PAUSE_CMDS);
                    }
                    ctx.getSource().sendFeedback(Component.literal("[AutoSand] " + (enabled ? "BẬT" : "TẮT")));
                    return 1;
                })
                .then(ClientCommandManager.literal("cmd")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String t = StringArgumentType.getString(ctx, "text").trim();
                            if (t.startsWith("/")) t = t.substring(1);
                            ORDER_COMMAND = t;
                            ctx.getSource().sendFeedback(Component.literal("[AutoSand] Lệnh orders: /" + ORDER_COMMAND));
                            return 1;
                        })))));
    }

    // ----------------------------- TICK -----------------------------
    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!enabled || p == null || mc.gameMode == null) return;
        if (wait > 0) { wait--; return; }

        if (state == S.MINING) {
            if (cooldown > 0) { cooldown--; return; }
            if (mc.screen == null && isFull(p) && countSand(p) > 0) {
                run(p, PAUSE_CMDS);
                send(p, "/" + ORDER_COMMAND);
                state = S.WAIT_ORDERS;
                timeout = STEP_TIMEOUT;
                clicks = 0;
                wait = ACTION_DELAY;
            }
            return;
        }

        if (--timeout <= 0) { abort(mc, "hết thời gian chờ ở bước " + state); return; }

        ChestMenu h = container(mc);

        switch (state) {
            case WAIT_ORDERS -> {
                // Danh sách đơn hàng: 6 hàng, ô 0 = đơn đầu tiên
                if (h == null || h.getRowCount() != 6) return;
                ItemStack first = h.slots.get(0).getItem();
                if (first.isEmpty()) return; // chưa load xong
                if (!first.is(Items.SAND)) { abort(mc, "ô đầu tiên không phải đơn cát"); return; }
                mc.gameMode.handleInventoryMouseClick(h.containerId, 0, 0, ClickType.PICKUP, p);
                lastSync = h.containerId;
                state = S.WAIT_DELIVER;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case WAIT_DELIVER -> {
                // GUI "bỏ vật phẩm vào để giao": cũng 6 hàng nhưng là cửa sổ mới
                if (h == null || h.containerId == lastSync || h.getRowCount() != 6) return;
                lastSync = h.containerId;
                state = S.DEPOSIT;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case DEPOSIT -> {
                if (h == null || h.containerId != lastSync) { abort(mc, "mất GUI giao hàng"); return; }
                if (clicks < 120) {
                    for (int i = 0; i < h.slots.size(); i++) {
                        Slot s = h.slots.get(i);
                        if (s.container == p.getInventory() && s.getItem().is(Items.SAND)) {
                            mc.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.QUICK_MOVE, p);
                            clicks++;
                            wait = 1; // mỗi 2 tick shift-click 1 ô
                            return;
                        }
                    }
                }
                // hết cát (hoặc GUI đầy) -> đóng để server mở màn hình xác nhận
                p.closeContainer();
                state = S.WAIT_CONFIRM;
                timeout = STEP_TIMEOUT;
                wait = ACTION_DELAY;
            }
            case WAIT_CONFIRM -> {
                // Màn hình "đơn hàng -> xác nhận giao hàng": 3 hàng
                if (h == null || h.containerId == lastSync || h.getRowCount() != 3) return;
                int slot = CONFIRM_SLOT;
                for (int i = 0; i < 27 && i < h.slots.size(); i++) {
                    ItemStack st = h.slots.get(i).getItem();
                    if (!st.isEmpty()) {
                        String n = st.getHoverName().getString().toLowerCase(Locale.ROOT);
                        if (n.contains("xác nhận") || n.contains("xac nhan")) { slot = i; break; }
                    }
                }
                mc.gameMode.handleInventoryMouseClick(h.containerId, slot, 0, ClickType.PICKUP, p);
                state = S.FINISH;
                wait = ACTION_DELAY * 3;
            }
            case FINISH -> {
                if (mc.screen != null) p.closeContainer();
                run(p, RESUME_CMDS);
                state = S.MINING;
                cooldown = COOLDOWN_AFTER;
                msg(p, "Đã giao xong, tiếp tục đào.");
            }
            default -> { }
        }
    }

    // ----------------------------- HELPERS -----------------------------
    static ChestMenu container(Minecraft mc) {
        if (mc.screen instanceof AbstractContainerScreen<?> sc
                && sc.getMenu() instanceof ChestMenu m) {
            return m;
        }
        return null;
    }

    /** Đầy = không còn ô trống và không còn stack cát nào chưa đủ 64. */
    static boolean isFull(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) return false;
            if (st.is(Items.SAND) && st.getCount() < st.getMaxStackSize()) return false;
        }
        return true;
    }

    static int countSand(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(Items.SAND)) n += st.getCount();
        }
        return n;
    }

    static void run(LocalPlayer p, List<String> cmds) {
        for (String c : cmds) send(p, c);
    }

    /** "/xxx" gửi như lệnh server; "#xxx" / ".xxx" sẽ bị Baritone / Meteor chặn ở client. */
    static void send(LocalPlayer p, String s) {
        if (s.startsWith("/")) p.connection.sendCommand(s.substring(1));
        else p.connection.sendChat(s);
    }

    static void msg(LocalPlayer p, String t) {
        p.displayClientMessage(Component.literal("[AutoSand] " + t), false);
    }

    static void abort(Minecraft mc, String why) {
        LocalPlayer p = mc.player;
        if (p != null) {
            if (mc.screen != null) p.closeContainer();
            run(p, RESUME_CMDS);
            msg(p, "Huỷ lượt giao: " + why);
        }
        state = S.MINING;
        cooldown = 200;
    }
}
