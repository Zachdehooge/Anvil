package main

import (
	"fmt"
	"log"
	"os"
	"os/signal"
	"time"

	"github.com/bwmarrin/discordgo"
	"github.com/joho/godotenv"
)

// Global Variables
var s *discordgo.Session

func init() {
	godotenv.Load()
	log.Print("Getting bot token from .env file")
	var BotToken = os.Getenv("TOKEN")
	var err error
	s, err = discordgo.New("Bot " + BotToken)
	if err != nil {
		log.Fatalf("Invalid bot parameters: %v | Check the .env", err)
	}
}

// Slash Commands
var (
	commands = []*discordgo.ApplicationCommand{
		{
			Name:        "location",
			Description: "Location to get tempest information",
		},
		{
			Name:        "tempeststatus",
			Description: "Status of a tempest ID",
			Options: []*discordgo.ApplicationCommandOption{
				{
					Type:        discordgo.ApplicationCommandOptionString,
					Name:        "tid",
					Description: "tempest ID",
					Required:    true,
				},
			},
		},
		{
			Name:        "tempestobs",
			Description: "Temperature at a tempest ID",
			/*Options: []*discordgo.ApplicationCommandOption{
				{
					Type:        discordgo.ApplicationCommandOptionString,
					Name:        "tid",
					Description: "tempest ID",
					Required:    true,
				},
			},*/
		},
		{
			Name:        "help",
			Description: "help command",
		},
	}

	commandHandlers = map[string]func(s *discordgo.Session, i *discordgo.InteractionCreate){
		"location": func(s *discordgo.Session, i *discordgo.InteractionCreate) {
			s.InteractionRespond(i.Interaction, &discordgo.InteractionResponse{
				Type: discordgo.InteractionResponseChannelMessageWithSource,
				Data: &discordgo.InteractionResponseData{
					Embeds: []*discordgo.MessageEmbed{
						{
							Title:       "Location",
							Description: fmt.Sprintf("Location selected: "),
							Color:       0x57F287,
						},
					},
				},
			})
		},
		"tempeststatus": func(s *discordgo.Session, i *discordgo.InteractionCreate) {
			tid := "212384"
			status, color, terr := TempestStatus(tid)

			var output = ""

			if status == 200 {
				output = fmt.Sprintf("%v", status)
			} else {
				output = fmt.Sprintf("%v\nError: %v", status, terr)
			}

			s.InteractionRespond(i.Interaction, &discordgo.InteractionResponse{
				Type: discordgo.InteractionResponseChannelMessageWithSource,
				Data: &discordgo.InteractionResponseData{
					Embeds: []*discordgo.MessageEmbed{
						{
							Title:       fmt.Sprintf("Status of %v", tid),
							Description: output,
							Color:       color,
						},
					},
				},
			})
		},
		"tempestobs": func(s *discordgo.Session, i *discordgo.InteractionCreate) {
			tid := "212384"
			timestamp, temp, dewpoint, feelslike, barpressure, pressuretrend, preciptoday, precipyesterday, _, _, color := TempestObs(tid)

			loc, err := time.LoadLocation("America/New_York")
			if err != nil {
				loc = time.UTC // fallback
			}

			t := time.Unix(timestamp, 0).In(loc)
			formattedTime := t.Format("15:04:05")

			s.InteractionRespond(i.Interaction, &discordgo.InteractionResponse{
				Type: discordgo.InteractionResponseChannelMessageWithSource,
				Data: &discordgo.InteractionResponseData{
					Embeds: []*discordgo.MessageEmbed{
						{
							Title:       fmt.Sprintf("Observations at Station - %v", tid),
							Description: fmt.Sprintf("Time: %v\nTemperature: %.2f°F\nDew Point: %.2f°F\nFeels Like: %.2f°F\nBarometric Pressure: %.2f\nPressure Trend: %v\nPrecip Today: %.2f in\nPrecip Yesterday: %.2f in", formattedTime, temp, dewpoint, feelslike, barpressure, pressuretrend, preciptoday, precipyesterday),
							Color:       color,
						},
					},
				},
			})
		},
		"help": func(s *discordgo.Session, i *discordgo.InteractionCreate) {
			s.InteractionRespond(i.Interaction, &discordgo.InteractionResponse{
				Type: discordgo.InteractionResponseChannelMessageWithSource,
				Data: &discordgo.InteractionResponseData{
					Embeds: []*discordgo.MessageEmbed{
						{
							Title: "List of Commands",
							Color: 0xFF0090,
							Fields: []*discordgo.MessageEmbedField{
								{
									Name:   "/location",
									Value:  "Location to get tempest information",
									Inline: false,
								},
								{
									Name:   "/tempeststatus",
									Value:  "Status of a tempest ID",
									Inline: false,
								},
								{
									Name:   "/tempestobs",
									Value:  "Temperature at a tempest ID",
									Inline: false,
								},
							},
						},
					},
				},
			})
		},
	}
)

func init() {
	s.AddHandler(func(s *discordgo.Session, i *discordgo.InteractionCreate) {
		if h, ok := commandHandlers[i.ApplicationCommandData().Name]; ok {
			h(s, i)
		}
	})
}

func main() {

	var GuildID = os.Getenv("GUILDID")

	s.AddHandler(func(s *discordgo.Session, r *discordgo.Ready) {
		go func() {
			ticker := time.NewTicker(1 * time.Minute)
			defer ticker.Stop()

			for {
				now := time.Now().UTC().Format("15:04")

				_ = s.UpdateStatusComplex(discordgo.UpdateStatusData{
					Activities: []*discordgo.Activity{
						{
							Name: now + " UTC",
							Type: discordgo.ActivityTypeWatching,
						},
					},
					Status: "online",
				})

				<-ticker.C
			}
		}()
	})

	err := s.Open()
	if err != nil {
		log.Fatalf("Cannot open the session: %v", err)
	}

	existing, err := s.ApplicationCommands(s.State.User.ID, GuildID)
	if err != nil {
		log.Fatalf("Failed to list existing commands: %v", err)
	}

	for _, cmd := range existing {
		err := s.ApplicationCommandDelete(s.State.User.ID, GuildID, cmd.ID)
		if err != nil {
			log.Printf("Failed to delete old command '%v': %v", cmd.Name, err)
		} else {
			//log.Printf("Deleted old command: %v", cmd.Name)
		}
	}

	log.Println("Adding commands...")
	registeredCommands := make([]*discordgo.ApplicationCommand, len(commands))
	for i, v := range commands {
		cmd, err := s.ApplicationCommandCreate(s.State.User.ID, GuildID, v)
		if err != nil {
			log.Panicf("Cannot create '%v' command: %v", v.Name, err)
		}
		registeredCommands[i] = cmd
	}

	log.Println("Refreshing commands...")
	_, err = s.ApplicationCommandBulkOverwrite(s.State.User.ID, GuildID, commands)
	if err != nil {
		log.Fatalf("Cannot refresh commands: %v", err)
	}

	defer s.Close()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt)
	log.Println("Ready to take commands!")
	log.Println("Press Ctrl+C to exit")
	<-stop
	log.Println("Shutting down...")
}
