package com.gatehousemc.platform.fabric.command;

import com.gatehousemc.application.admin.AdminCommand;
import com.gatehousemc.application.admin.AdminCommandParser;
import com.gatehousemc.application.admin.AdminCommandResult;
import com.gatehousemc.application.admin.AdminResultRenderer;
import com.gatehousemc.application.admin.RequestReference;
import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.platform.fabric.FabricRuntime;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Supplier;

/** Fabric's transport adapter for the shared Gatehouse command model. */
public final class GatehouseCommands {
    private GatehouseCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<FabricRuntime> runtimeSupplier) {
        dispatcher.register(buildCommand(runtimeSupplier));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildCommand(
            Supplier<FabricRuntime> runtimeSupplier) {
        return Commands.literal("gatehouse")
                .requires(source -> {
                    FabricRuntime runtime = runtimeSupplier.get();
                    int level = runtime == null ? 2 : runtime.config().minecraft().viewPermissionLevel();
                    return has(source, level);
                })
                .executes(GatehouseCommands::help)
                .then(Commands.literal("help").executes(GatehouseCommands::help))
                .then(Commands.literal("requests")
                        .executes(context -> requests(context, runtimeSupplier, "pending"))
                        .then(Commands.argument("status", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        new String[]{"pending", "resolving", "approved", "denied", "blocked", "all"}, builder))
                                .executes(context -> requests(context, runtimeSupplier,
                                        context.getArgument("status", String.class)))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> requests(context, runtimeSupplier,
                                                context.getArgument("status", String.class))))))
                .then(referenceAction("show", AdminCommand.Kind.SHOW, runtimeSupplier, false))
                .then(referenceAction("approve", AdminCommand.Kind.APPROVE, runtimeSupplier, true))
                .then(referenceAction("deny", AdminCommand.Kind.DENY, runtimeSupplier, true))
                .then(referenceAction("block", AdminCommand.Kind.BLOCK, runtimeSupplier, true))
                .then(referenceAction("reopen", AdminCommand.Kind.REOPEN, runtimeSupplier, true))
                .then(referenceAction("undo", AdminCommand.Kind.UNDO, runtimeSupplier, true))
                .then(Commands.literal("unblock")
                        .then(Commands.argument("username", StringArgumentType.word())
                                .executes(context -> executeText(context, runtimeSupplier,
                                        "unblock " + context.getArgument("username", String.class)))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "unblock " + context.getArgument("username", String.class) + " "
                                                        + context.getArgument("reason", String.class))))))
                .then(Commands.literal("status").executes(context -> status(context, runtimeSupplier)))
                .then(Commands.literal("reload").executes(context -> reload(context, runtimeSupplier)))
                .then(Commands.literal("provider")
                        .then(Commands.literal("status")
                                .executes(context -> executeText(context, runtimeSupplier, "provider status"))
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "provider status " + context.getArgument("provider", String.class)))))
                        .then(Commands.literal("test")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "provider test " + context.getArgument("provider", String.class))))))
                .then(Commands.literal("setup")
                        .then(Commands.literal("status").executes(context -> executeText(context, runtimeSupplier, "setup status")))
                        .then(Commands.literal("discord").executes(context -> executeText(context, runtimeSupplier, "setup discord")))
                        .then(Commands.literal("telegram").executes(context -> executeText(context, runtimeSupplier, "setup telegram")))
                        .then(Commands.literal("bind")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .then(Commands.argument("code", StringArgumentType.word())
                                                .executes(context -> executeText(context, runtimeSupplier,
                                                        "setup bind " + context.getArgument("provider", String.class) + " "
                                                                + context.getArgument("code", String.class))))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "setup cancel " + context.getArgument("provider", String.class))))))
                .then(Commands.literal("admin")
                        .then(Commands.literal("list")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .executes(context -> executeText(context, runtimeSupplier,
                                                "admin list " + context.getArgument("provider", String.class)))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .then(Commands.argument("principal", StringArgumentType.word())
                                                .then(Commands.argument("access", StringArgumentType.word())
                                                        .executes(context -> executeText(context, runtimeSupplier,
                                                                "admin add " + context.getArgument("provider", String.class) + " "
                                                                        + context.getArgument("principal", String.class) + " "
                                                                        + context.getArgument("access", String.class)))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .then(Commands.argument("principal", StringArgumentType.word())
                                                .executes(context -> executeText(context, runtimeSupplier,
                                                        "admin remove " + context.getArgument("provider", String.class) + " "
                                                                + context.getArgument("principal", String.class)))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> referenceAction(
            String name, AdminCommand.Kind kind, Supplier<FabricRuntime> supplier, boolean reasonAllowed) {
        var request = Commands.argument("request", StringArgumentType.word())
                .executes(context -> executeReference(context, supplier, kind, ""));
        if (reasonAllowed) {
            request.then(Commands.argument("reason", StringArgumentType.greedyString())
                    .executes(context -> executeReference(context, supplier, kind,
                            context.getArgument("reason", String.class))));
        }
        return Commands.literal(name).then(request);
    }

    private static int requests(CommandContext<CommandSourceStack> context, Supplier<FabricRuntime> supplier, String statusText) {
        try {
            int page = 1;
            try { page = context.getArgument("page", Integer.class); } catch (IllegalArgumentException ignored) { }
            RequestStatus status = statusText.equalsIgnoreCase("all") ? null
                    : RequestStatus.valueOf(statusText.toUpperCase(Locale.ROOT));
            return execute(context, supplier, AdminCommand.base(AdminCommand.Kind.REQUESTS).withStatus(status, page));
        } catch (IllegalArgumentException error) {
            return fail(context, "Invalid request status or page: " + error.getMessage());
        }
    }

    private static int executeReference(CommandContext<CommandSourceStack> context, Supplier<FabricRuntime> supplier,
                                        AdminCommand.Kind kind, String reason) {
        try {
            return execute(context, supplier, AdminCommand.base(kind).withRequest(
                    RequestReference.parse(context.getArgument("request", String.class)), reason));
        } catch (IllegalArgumentException error) {
            return fail(context, error.getMessage());
        }
    }

    private static int executeText(CommandContext<CommandSourceStack> context, Supplier<FabricRuntime> supplier, String text) {
        AdminCommandResult parsed = AdminCommandParser.parse(text);
        if (!parsed.succeeded()) return fail(context, parsed.message());
        return execute(context, supplier, (AdminCommand) parsed.value().orElseThrow());
    }

    private static int execute(CommandContext<CommandSourceStack> context, Supplier<FabricRuntime> supplier,
                               AdminCommand command) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.adminCommands() == null || runtime.commandExecutor() == null) {
            return fail(context, Messages.get("command.not_available"));
        }
        CommandSourceStack source = context.getSource();
        runtime.commandExecutor().execute(() -> runtime.adminCommands().execute(command, principalOf(source))
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) source.sendFailure(Component.literal(Messages.get("command.decision_failed", safeMessage(error))));
                    else if (result.succeeded()) source.sendSuccess(() -> Component.literal(AdminResultRenderer.render(result)), false);
                    else source.sendFailure(Component.literal(result.message()));
                })));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context, Supplier<FabricRuntime> supplier) {
        FabricRuntime runtime = supplier.get();
        if (runtime == null || runtime.status() == null) return fail(context, Messages.get("command.not_available"));
        var snapshot = runtime.status().snapshot();
        return feedback(context, "Gatehouse " + snapshot.version() + " health=" + snapshot.health()
                + " runtime=" + snapshot.runtimeState() + " active=" + snapshot.activeRequests()
                + " queue=" + snapshot.queueCurrent() + "/" + snapshot.queueCapacity()
                + " outbox=" + snapshot.pendingOutbox());
    }

    private static int reload(CommandContext<CommandSourceStack> context, Supplier<FabricRuntime> supplier) {
        return executeText(context, supplier, "reload");
    }

    private static AdminPrincipal principalOf(CommandSourceStack source) {
        if (source.getEntity() == null) return AdminPrincipal.console();
        int level = has(source, 4) ? 4 : has(source, 3) ? 3 :
                has(source, 2) ? 2 : has(source, 1) ? 1 : 0;
        return new AdminPrincipal("minecraft", "perm:" + level, source.getTextName());
    }

    private static boolean has(CommandSourceStack source, int level) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        return feedback(context, Messages.get("command.help"));
    }

    private static int feedback(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message == null || message.isBlank() ? "Invalid Gatehouse command" : message));
        return 0;
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
