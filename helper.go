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
	Time               int64   `json:"timestamp"`
	Temperature        float64 `json:"air_temperature"`
	DewPoint           float64 `json:"dew_point"`
	FeelsLike          float64 `json:"feels_like"`
	BarometricPressure float64 `json:"barometric_pressure"`
	PressureTrend      string  `json:"pressure_trend"`
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

func TempestObs(tid string) (int64, float64, float64, float64, float64, string, string, int, int) {
	var TempestToken = os.Getenv("TEMPEST_TOKEN")

	status, color, _ := TempestStatus(tid)

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

	if status == 200 && len(data.Observations) > 0 {
		obs := data.Observations[0]
		return obs.Time, (obs.Temperature * 1.8) + 32, (obs.DewPoint * 1.8) + 32, (obs.FeelsLike * 1.8) + 32, obs.BarometricPressure, obs.PressureTrend, "", status, color
	}

	return 0, 0, 0, 0, 0, "", "Tempest did not return a value", 0, color
}
