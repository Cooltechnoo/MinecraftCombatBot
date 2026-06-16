package de.Cooltechno.telavivfx.client;

import de.Cooltechno.telavivfx.features.CombatBot;
import de.Cooltechno.telavivfx.features.SwordBot;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class TelavivfxClient implements ClientModInitializer {
    private final CombatBot combatBot = new CombatBot();
    private final SwordBot swordBot = new SwordBot();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (combatBot.enabled) {
                combatBot.onTick(client);
            }
            if (swordBot.enabled) {
                swordBot.onTick(client);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("bot")
                    // Running just "/bot" shows the menu and status
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal("=== Bot Status ===").formatted(Formatting.GOLD));
                        context.getSource().sendFeedback(Text.literal("Crystal/Anchor Bot: ").append(getFormattedStatus(combatBot.enabled)));
                        context.getSource().sendFeedback(Text.literal("Sword Pvp Bot: ").append(getFormattedStatus(swordBot.enabled)));
                        context.getSource().sendFeedback(Text.literal("Use /bot <crystal|sword> to toggle!").formatted(Formatting.GRAY));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("mode", StringArgumentType.string())
                            .executes(context -> {
                                String mode = StringArgumentType.getString(context, "mode").toLowerCase();

                                switch (mode) {
                                    case "crystal":
                                    case "anchor":
                                    case "combat":
                                        combatBot.enabled = !combatBot.enabled;
                                        context.getSource().sendFeedback(Text.literal("Crystal Bot is now ").append(getFormattedStatus(combatBot.enabled)));
                                        break;

                                    case "sword":
                                    case "pvp":
                                    case "melee":
                                        swordBot.enabled = !swordBot.enabled;
                                        context.getSource().sendFeedback(Text.literal("Sword Bot is now ").append(getFormattedStatus(swordBot.enabled)));
                                        break;

                                    default:
                                        context.getSource().sendError(Text.literal("Unknown bot type! Use 'crystal' or 'sword'."));
                                        break;
                                }
                                return 1;
                            })));
        });
    }

    private Text getFormattedStatus(boolean isEnabled) {
        String label = isEnabled ? "ON" : "OFF";
        Formatting color = isEnabled ? Formatting.GREEN : Formatting.RED;
        return Text.literal(label).formatted(color);
    }
}