package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"

	"github.com/joho/godotenv"
)

type Response struct {
	Observations []Observations `json:"obs"`
}

type Observations struct {
	Temperature float64 `json:"air_temperature"`
}

func TempestStatus(tid string) (int, int, string) {
	var TempestToken = os.Getenv("TEMPEST_TOKEN")
	err := godotenv.Load()
	if err != nil {
		return 0, 0, "Failed to return Tempest Token"
	}

	url := "https://swd.weatherflow.com/swd/rest/stations/" + tid + "?token=" + TempestToken
	resp, err := http.Get(url)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, 0xFF0000, string(b)
	}

	if resp.StatusCode == 200 {
		return resp.StatusCode, 0x57F287, string(b)
	}
	return resp.StatusCode, 0xFF0000, string(b)
}

func TempestTemp(tid string) (float64, string, int) {
	var TempestToken = os.Getenv("TEMPEST_TOKEN")

	status, _, _ := TempestStatus(tid)

	url := "https://swd.weatherflow.com/swd/rest/observations/station/" + tid + "?token=" + TempestToken
	resp, err := http.Get(url)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	var data Response

	err = json.NewDecoder(resp.Body).Decode(&data)
	if err != nil {
		panic(err)
	}

	if status == 200 {
		return (data.Observations[0].Temperature * 1.8) + 32, "", status
	}
	return 0, "TempestTemp() did not return a value", status
}
