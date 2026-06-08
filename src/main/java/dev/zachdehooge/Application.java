package dev.zachdehooge;

import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;

import java.util.EnumSet;
import java.util.Objects;

import static net.dv8tion.jda.api.interactions.commands.OptionType.*;

public class Application extends ListenerAdapter {

    public String token() {
        Dotenv dotenv = Dotenv.load();
        return dotenv.get("TOKEN");
    }

    public void Run() {
        EnumSet<GatewayIntent> intents = EnumSet.noneOf(GatewayIntent.class);
        JDA jda = JDABuilder.createLight(token(), intents)
                .addEventListeners(new Application())
                .build();

        CommandListUpdateAction commands = jda.updateCommands();

        // Simple reply commands
        commands.addCommands(Commands.slash("say", "Makes the bot say what you tell it to")
                // Allow the command to be used anywhere (Bot DMs, Guild, Friend DMs, Group DMs)
                .setContexts(InteractionContextType.ALL)
                // Allow the command to be installed anywhere (Guilds, Users)
                .setIntegrationTypes(IntegrationType.ALL)
                // you can add required options like this too
                .addOption(STRING, "content", "What the bot should say", true));

        commands.queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        // Only accept commands from guilds
        if (event.getGuild() == null) {
            return;
        }
        if (event.getName().equals("say")) {// content is required so no null-check here
            say(event, Objects.requireNonNull(event.getOption("content")).getAsString());
        } else {
            event.reply("I can't handle that command right now :(")
                    .setEphemeral(true)
                    .queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        // this is the custom id we specified in our button
        String[] id = event.getComponentId().split(":");
        String authorId = id[0];
        String type = id[1];
        // Check that the button is for the user that clicked it, otherwise just ignore the event (let interaction fail)
        if (!authorId.equals(event.getUser().getId())) {
            return;
        }

        // acknowledge the button was clicked, otherwise the interaction will fail
        event.deferEdit().queue();

        MessageChannel channel = event.getChannel();
    }

    public void say(SlashCommandInteractionEvent event, String content) {
        // This requires no permissions!
        event.reply(content).queue();
    }
}