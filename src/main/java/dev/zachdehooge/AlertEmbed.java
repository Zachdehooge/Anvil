package dev.zachdehooge;

import net.dv8tion.jda.api.entities.MessageEmbed;

public record AlertEmbed(String id, MessageEmbed embed, String fullDescription, String eventName, String historyImageUrl) {
    public AlertEmbed(String id, MessageEmbed embed, String fullDescription, String eventName) {
        this(id, embed, fullDescription, eventName, null);
    }
}
