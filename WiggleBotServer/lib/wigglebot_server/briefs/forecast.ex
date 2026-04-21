defmodule WigglebotServer.Briefs.Forecast do
  @moduledoc "Fetches and slices Open-Meteo hourly forecast data."

  def fetch_hourly(lat, lon) do
    url =
      "https://api.open-meteo.com/v1/forecast" <>
        "?latitude=#{lat}&longitude=#{lon}" <>
        "&hourly=temperature_2m,apparent_temperature,precipitation_probability,weathercode,windspeed_10m" <>
        "&timezone=auto&forecast_days=1"

    case Req.get(url, receive_timeout: 10_000) do
      {:ok, %{status: 200, body: %{"hourly" => hourly}}} -> {:ok, hourly}
      {:ok, %{status: s}} -> {:error, "Open-Meteo HTTP #{s}"}
      {:error, e} -> {:error, inspect(e)}
    end
  end

  # Returns a list of per-hour maps for hours in [start_h, end_h] (inclusive).
  def window(hourly, start_h, end_h) do
    hourly["time"]
    |> Enum.with_index()
    |> Enum.filter(fn {t, _} ->
      h = t |> String.split("T") |> List.last() |> String.split(":") |> hd() |> String.to_integer()
      h >= start_h and h <= end_h
    end)
    |> Enum.map(fn {_, i} ->
      %{
        temp: Enum.at(hourly["temperature_2m"], i) || 0,
        feels_like: Enum.at(hourly["apparent_temperature"], i) || 0,
        precip_prob: Enum.at(hourly["precipitation_probability"], i) || 0,
        wind: Enum.at(hourly["windspeed_10m"], i) || 0,
        code: Enum.at(hourly["weathercode"], i) || 0
      }
    end)
  end

  # Summarises a window to worst-case conditions (max precip/wind/code, avg temp).
  def summarise([]), do: nil

  def summarise(hours) do
    n = length(hours)

    %{
      temp: round(Enum.sum(Enum.map(hours, & &1.temp)) / n),
      feels_like: round(Enum.sum(Enum.map(hours, & &1.feels_like)) / n),
      precip_prob: hours |> Enum.map(& &1.precip_prob) |> Enum.max(),
      wind: hours |> Enum.map(& &1.wind) |> Enum.max() |> round(),
      code: hours |> Enum.map(& &1.code) |> Enum.max()
    }
  end

  def desc(code) do
    cond do
      code == 0 -> "clear"
      code in 1..2 -> "partly cloudy"
      code == 3 -> "overcast"
      code in 45..48 -> "foggy"
      code in 51..55 -> "drizzle"
      code in 61..65 -> "rain"
      code in 66..67 -> "freezing rain"
      code in 71..75 -> "snow"
      code in 80..82 -> "showers"
      code in 85..86 -> "snow showers"
      code in 95..99 -> "thunderstorm"
      true -> "mixed"
    end
  end

  # Actual rain (not drizzle/mist) — codes 61+
  def rain?(code), do: code in (61..67) or code in (80..82) or code in (95..99)
  # Heavy precip — skip-worthy
  def heavy_precip?(code), do: code in [63, 65, 66, 67, 80, 81, 82, 85, 86, 95, 96, 99]
  # Light drizzle / mist — user says this is fine for running
  def mist?(code), do: code in 51..55
end
